(ns autho.real-integration-abac-test
  "Tests ABAC end-to-end avec de vraies implémentations : LDAP, Kafka et REST.
   Aucun mock n'est utilisé — les services externes doivent être actifs.

   Prérequis (docker-compose up -d) :
   - OpenLDAP sur le port 389  (utilisateurs 001–008 avec role, service, clearance-level)
   - Kafka sur le port 9092    (topic créé automatiquement si absent)

   Le test ouvre sa propre base RocksDB dans /tmp/rocksdb/test-abac-integration
   et démarre un serveur HTTP embarqué (JDK) sur le port 19876 pour le PIP REST.

   Exécution : lein test :integration"
  (:require [clojure.test    :refer :all]
            [clojure.string  :as str]
            [clojure.data.json :as json]
            [autho.pdp       :as pdp]
            [autho.prp       :as prp]
            [autho.ldap      :as ldap]
            [autho.person    :as person]
            [autho.kafka-pip-unified :as kpu])
  (:import [com.sun.net.httpserver HttpServer HttpHandler]
           [java.net InetSocketAddress]
           [org.apache.kafka.clients.producer KafkaProducer ProducerConfig ProducerRecord]
           [org.apache.kafka.clients.admin AdminClient NewTopic]
           [java.util Properties]))

;; =============================================================================
;; Configuration
;; =============================================================================

(def ^:private kafka-bootstrap  "localhost:9092")
(def ^:private test-kafka-topic "test-abac-dossiers-integration")
(def ^:private test-db-path     "/tmp/rocksdb/test-abac-integration")
(def ^:private rest-http-port   19876)

(def ^:private ldap-config
  {:type :ldap
   :props {:ldap.server        "localhost"
           :ldap.port          389
           :ldap.connectstring "cn=admin,dc=example,dc=com"
           :ldap.password      "admin"
           :ldap.basedn        "ou=people,dc=example,dc=com"
           :ldap.filter        "(objectClass=inetOrgPerson)"
           :ldap.attributes    "uid,cn,role,service,seuil,clearance-level"}})

;; =============================================================================
;; Jeu de données de test
;; =============================================================================

;; DossierMedical — stockés dans Kafka/RocksDB via le PIP unifié
(def ^:private dossier-kafka-data
  [{:class "DossierMedical" :id "DM-001" :service "service1" :niveau-requis 2 :statut "actif"}
   {:class "DossierMedical" :id "DM-002" :service "service2" :niveau-requis 2 :statut "actif"}
   {:class "DossierMedical" :id "DM-003" :service "service1" :niveau-requis 1 :statut "archive"}
   {:class "DossierMedical" :id "DM-004" :service "service3" :niveau-requis 5 :statut "actif"}])

;; Rapport — servis par le serveur HTTP embarqué (PIP REST)
(def ^:private rapport-rest-data
  {"RAP-001" {:id "RAP-001" :type "bilan"        :confidentialite "publique"}
   "RAP-002" {:id "RAP-002" :type "compte-rendu" :confidentialite "confidentielle"}
   "RAP-003" {:id "RAP-003" :type "audit"        :confidentialite "hautement-confidentielle"}})

;; =============================================================================
;; Politiques — les conditions utilisent des symboles EDN réels grâce à
;; la syntaxe de citation Clojure '= '>= 'in etc.
;; =============================================================================

;;
;; DossierMedical :
;;   R-DM-ALLOW       (allow, priorité 0)  : service identical + clearance >= niveau-requis
;;   R-DM-DENY-ARCHIVE (deny,  priorité 10) : dossier archivé
;;
;; Stratégie almost_one_allow_no_deny :
;;   si deny-priority > allow-priority → DENY
;;
(def ^:private policy-dossier-medical
  {:strategy :almost_one_allow_no_deny
   :rules
   [{:name      "R-DM-ALLOW"
     :priority  0
     :operation "lire"
     :effect    "allow"
     :conditions [['=  '[Person         $s service]        '[DossierMedical $r service]]
                  ['>= '[Person         $s clearance-level] '[DossierMedical $r niveau-requis]]]}
    {:name      "R-DM-DENY-ARCHIVE"
     :priority  10
     :operation "lire"
     :effect    "deny"
     :conditions [['= '[DossierMedical $r statut] "archive"]]}]})

;;
;; Rapport :
;;   R-RAP-LIRE    (allow, priorité 0) : role in "DPO,chef_de_service,legal-counsel"
;;   R-RAP-DENY-HC (deny,  priorité 5) : confidentialite == "hautement-confidentielle"
;;   R-RAP-MOD     (allow, priorité 0) : role == "DPO"  (opération "modifier")
;;
(def ^:private policy-rapport
  {:strategy :almost_one_allow_no_deny
   :rules
   [{:name      "R-RAP-LIRE"
     :priority  0
     :operation "lire"
     :effect    "allow"
     :conditions [['in '[Person $s role] "DPO,chef_de_service,legal-counsel"]]}
    {:name      "R-RAP-DENY-HC"
     :priority  5
     :operation "lire"
     :effect    "deny"
     :conditions [['= '[Rapport $r confidentialite] "hautement-confidentielle"]]}
    {:name      "R-RAP-MOD"
     :priority  0
     :operation "modifier"
     :effect    "allow"
     :conditions [['= '[Person $s role] "DPO"]]}]})

;; =============================================================================
;; Vérification de disponibilité des services
;; =============================================================================

(defn- kafka-available? []
  (try
    (let [props (doto (Properties.) (.put "bootstrap.servers" kafka-bootstrap))]
      (with-open [admin (AdminClient/create props)]
        (-> admin .describeCluster .nodes (.get))
        true))
    (catch Exception _ false)))

(defn- ldap-available? []
  (try
    (if @ldap/ldap-server
      true
      (do (ldap/init (:props ldap-config))
          (boolean @ldap/ldap-server)))
    (catch Exception _ false)))

(defn- all-deps-available? []
  (and (ldap-available?) (kafka-available?)))

;; =============================================================================
;; Utilitaires Kafka
;; =============================================================================

(defn- cleanup-test-db []
  (let [dir (java.io.File. test-db-path)]
    (when (.exists dir)
      (doseq [f (.listFiles dir)] (.delete f))
      (.delete dir))))

(defn- ensure-topic [topic]
  (let [props (doto (Properties.) (.put "bootstrap.servers" kafka-bootstrap))]
    (with-open [admin (AdminClient/create props)]
      (let [existing (-> admin .listTopics .names (.get))]
        (when-not (contains? existing topic)
          (.createTopics admin [(NewTopic. topic 1 (short 1))])
          (Thread/sleep 2000))))))

(defn- create-kafka-producer []
  (let [props (doto (Properties.)
                (.put ProducerConfig/BOOTSTRAP_SERVERS_CONFIG kafka-bootstrap)
                (.put ProducerConfig/KEY_SERIALIZER_CLASS_CONFIG
                      "org.apache.kafka.common.serialization.StringSerializer")
                (.put ProducerConfig/VALUE_SERIALIZER_CLASS_CONFIG
                      "org.apache.kafka.common.serialization.StringSerializer")
                (.put ProducerConfig/ACKS_CONFIG "all"))]
    (KafkaProducer. props)))

(defn- produce-dossiers
  "Envoie les objets DossierMedical au topic Kafka de test.
   Le format attendu par kafka-pip-unified :
   {'class':'DossierMedical','id':'DM-001',...attributs...}"
  [producer]
  (doseq [data dossier-kafka-data]
    (let [msg (json/write-str (into {} (map (fn [[k v]] [(name k) v]) data)))]
      @(.send producer (ProducerRecord. test-kafka-topic (:id data) msg)))))

;; =============================================================================
;; Serveur HTTP embarqué (PIP REST pour Rapport)
;; =============================================================================

(defn- make-rapport-handler []
  (reify HttpHandler
    (handle [_ exchange]
      (let [path   (.getPath (.getRequestURI exchange))
            id     (last (str/split path #"/"))
            data   (get rapport-rest-data id)
            body   (if data
                     (.getBytes (json/write-str
                                 (into {} (map (fn [[k v]] [(name k) v]) data)))
                                "UTF-8")
                     (.getBytes "{}" "UTF-8"))
            status (if data 200 404)]
        (.sendResponseHeaders exchange status (count body))
        (with-open [os (.getResponseBody exchange)]
          (.write os body))))))

(defn- start-rapport-server
  "Démarre un HttpServer JDK sur rest-http-port, expose GET /api/rapports/{id}."
  []
  (let [server (HttpServer/create (InetSocketAddress. rest-http-port) 0)]
    (.createContext server "/api/rapports/" (make-rapport-handler))
    (.start server)
    server))

;; =============================================================================
;; État global de la fixture
;; =============================================================================

(def ^:private saved-pips    (atom nil))
(def ^:private http-server   (atom nil))

(defn- test-pips
  "Configuration des PIPs utilisés pendant les tests :
   - Kafka PIP unifié pour DossierMedical (base RocksDB de test)
   - REST PIP pour Rapport (serveur HTTP embarqué)"
  []
  [{:type                    :kafka-pip-unified
    :kafka-topic             test-kafka-topic
    :kafka-bootstrap-servers kafka-bootstrap
    :classes                 ["DossierMedical"]}
   {:class "Rapport"
    :pip   {:type :rest
            :url  (str "http://localhost:" rest-http-port "/api/rapports")
            :verb "get"}}])

;; =============================================================================
;; Fixture :once — mise en place et démontage de l'infrastructure réelle
;; =============================================================================

(defn- with-real-infrastructure [f]
  (if-not (all-deps-available?)
    (do
      (println "\n⚠  Skipping real integration tests: LDAP ou Kafka non disponible.")
      (println "   Démarrer avec : docker-compose up -d\n")
      (is true "Skipped — infrastructure unavailable"))
    (do
      ;; 1. Charger les personnes depuis le vrai annuaire LDAP
      (println "→ Chargement des personnes depuis LDAP...")
      (person/loadPersons ldap-config)
      (println (str "  " (count @prp/personSingleton) " personnes chargées."))

      ;; 2. Enregistrer les politiques de test
      (prp/insert-policy "DossierMedical" {:global policy-dossier-medical})
      (prp/insert-policy "Rapport"        {:global policy-rapport})

      ;; 3. Substituer la configuration des PIPs
      (reset! saved-pips @prp/pips)
      (reset! prp/pips (test-pips))

      ;; 4. Démarrer le serveur HTTP embarqué (REST PIP)
      (println "→ Démarrage du serveur HTTP (Rapport REST PIP) sur port" rest-http-port "...")
      (reset! http-server (start-rapport-server))

      ;; 5. Kafka : arrêter le PIP production, ouvrir la base de test, démarrer un consommateur
      (println "→ Initialisation du PIP Kafka sur" test-kafka-topic "...")
      (kpu/stop-unified-pip)
      (cleanup-test-db)
      (ensure-topic test-kafka-topic)
      (kpu/open-shared-db test-db-path ["DossierMedical"])
      (kpu/start-unified-consumer {:kafka-bootstrap-servers kafka-bootstrap
                                   :kafka-topic             test-kafka-topic})

      ;; 6. Produire les données de test
      (println "→ Production des DossierMedical dans Kafka...")
      (let [producer (create-kafka-producer)]
        (produce-dossiers producer)
        (.close producer))

      ;; 7. Attendre que le consommateur ait traité les messages
      (println "→ Attente du traitement Kafka (5 s)...")
      (Thread/sleep 5000)
      (println "→ Infrastructure prête. Lancement des tests.\n")

      (try
        (f)
        (finally
          ;; Démontage
          (println "\n→ Nettoyage de l'infrastructure de test...")
          (kpu/stop-unified-pip)
          (cleanup-test-db)
          (.stop @http-server 0)
          (reset! prp/pips @saved-pips)
          (prp/delete-policy "DossierMedical")
          (prp/delete-policy "Rapport")
          (println "→ Nettoyage terminé."))))))

(use-fixtures :once with-real-infrastructure)

;; =============================================================================
;; Helper d'évaluation E2E (pas de mock, appel direct du PDP)
;; =============================================================================

(defn- e2e
  "Évalue une requête d'autorisation via pdp/evalRequest sans aucun mock.
   Retourne true (allow) ou false/nil (deny)."
  [subject-id resource-class resource-id operation]
  (let [request {:subject   {:id subject-id   :class "Person"}
                 :resource  {:id resource-id  :class resource-class}
                 :operation operation
                 :context   {}}]
    (:result (pdp/evalRequest request))))

;; =============================================================================
;; Tests — DossierMedical (Kafka PIP unifié)
;; Utilisateurs LDAP réels :
;;   001 Paul         chef_de_service  service1  clearance=2
;;   002 John         agent            service2  clearance=1
;;   003 Alice        chef_de_service  service2  clearance=3
;;   004 Sophie       DPO              service1  clearance=4
;;   005 Marc         legal-counsel    service3  clearance=5
;;   006 Emma         comptable        service1  clearance=2
;;
;; DossierMedical (Kafka) :
;;   DM-001  service1  niveau-requis=2  statut=actif
;;   DM-002  service2  niveau-requis=2  statut=actif
;;   DM-003  service1  niveau-requis=1  statut=archive
;;   DM-004  service3  niveau-requis=5  statut=actif
;; =============================================================================

(deftest ^:integration dm-service-match-clearance-exact
  (testing "Paul (service1, clearance=2) lire DM-001 (service1, niveau=2) → ALLOW [2>=2, même service]"
    (is (true? (e2e "001" "DossierMedical" "DM-001" "lire")))))

(deftest ^:integration dm-service-mismatch-subject-side
  (testing "John (service2) lire DM-001 (service1) → DENY [service différent]"
    (is (false? (e2e "002" "DossierMedical" "DM-001" "lire")))))

(deftest ^:integration dm-service-mismatch-resource-side
  (testing "Paul (service1) lire DM-002 (service2) → DENY [service différent]"
    (is (false? (e2e "001" "DossierMedical" "DM-002" "lire")))))

(deftest ^:integration dm-alice-service2-clearance-higher
  (testing "Alice (service2, clearance=3) lire DM-002 (service2, niveau=2) → ALLOW [3>=2]"
    (is (true? (e2e "003" "DossierMedical" "DM-002" "lire")))))

(deftest ^:integration dm-clearance-insufficient
  (testing "John (service2, clearance=1) lire DM-002 (service2, niveau=2) → DENY [1<2]"
    (is (false? (e2e "002" "DossierMedical" "DM-002" "lire")))))

(deftest ^:integration dm-archive-deny-overrides-allow
  (testing "Paul (service1, clearance=2) lire DM-003 (service1, niveau=1, archive) → DENY
            [R-DM-ALLOW priority=0 ; R-DM-DENY-ARCHIVE priority=10 → deny gagne]"
    (is (false? (e2e "001" "DossierMedical" "DM-003" "lire")))))

(deftest ^:integration dm-high-clearance-required-match
  (testing "Marc (service3, clearance=5) lire DM-004 (service3, niveau=5) → ALLOW [5>=5]"
    (is (true? (e2e "005" "DossierMedical" "DM-004" "lire")))))

(deftest ^:integration dm-wrong-service-high-clearance
  (testing "Marc (service3, clearance=5) lire DM-001 (service1, niveau=2) → DENY [service différent]"
    (is (false? (e2e "005" "DossierMedical" "DM-001" "lire")))))

(deftest ^:integration dm-emma-service1-clearance-exact
  (testing "Emma (service1, clearance=2) lire DM-001 (service1, niveau=2) → ALLOW [2>=2]"
    (is (true? (e2e "006" "DossierMedical" "DM-001" "lire")))))

(deftest ^:integration dm-sophie-service1-clearance-higher
  (testing "Sophie (service1, clearance=4) lire DM-001 (service1, niveau=2) → ALLOW [4>=2]"
    (is (true? (e2e "004" "DossierMedical" "DM-001" "lire")))))

;; =============================================================================
;; Tests — Rapport (PIP REST + opérateur in + resolve-attr sur map)
;; Utilisateurs LDAP réels :
;;   001 Paul    chef_de_service
;;   002 John    agent
;;   004 Sophie  DPO
;;   005 Marc    legal-counsel
;;   006 Emma    comptable
;;
;; Rapport (REST HTTP) :
;;   RAP-001  confidentialite=publique
;;   RAP-002  confidentialite=confidentielle
;;   RAP-003  confidentialite=hautement-confidentielle
;;
;; Règles :
;;   R-RAP-LIRE    (allow, p=0) : role in "DPO,chef_de_service,legal-counsel"
;;   R-RAP-DENY-HC (deny,  p=5) : confidentialite == "hautement-confidentielle"
;;   R-RAP-MOD     (allow, p=0) : role == "DPO"  (opération modifier)
;; =============================================================================

(deftest ^:integration rapport-chef-lire-public
  (testing "Paul (chef_de_service) lire RAP-001 (publique) → ALLOW [role in list, pas de deny]"
    (is (true? (e2e "001" "Rapport" "RAP-001" "lire")))))

(deftest ^:integration rapport-agent-denied
  (testing "John (agent) lire RAP-001 → DENY [role not in list]"
    (is (false? (e2e "002" "Rapport" "RAP-001" "lire")))))

(deftest ^:integration rapport-dpo-lire-public
  (testing "Sophie (DPO) lire RAP-001 (publique) → ALLOW [DPO in list]"
    (is (true? (e2e "004" "Rapport" "RAP-001" "lire")))))

(deftest ^:integration rapport-legal-counsel-lire-public
  (testing "Marc (legal-counsel) lire RAP-001 (publique) → ALLOW"
    (is (true? (e2e "005" "Rapport" "RAP-001" "lire")))))

(deftest ^:integration rapport-comptable-denied
  (testing "Emma (comptable) lire RAP-001 → DENY [role not in list]"
    (is (false? (e2e "006" "Rapport" "RAP-001" "lire")))))

(deftest ^:integration rapport-chef-lire-confidentiel
  (testing "Paul (chef_de_service) lire RAP-002 (confidentielle) → ALLOW [role match, pas de deny HC]"
    (is (true? (e2e "001" "Rapport" "RAP-002" "lire")))))

;; Tests de l'opérateur = sur attribut REST PIP (resolve-attr sur map retournée)
(deftest ^:integration rapport-hc-deny-beats-allow-chef
  (testing "Paul (chef_de_service) lire RAP-003 (hautement-confidentielle) → DENY
            [R-RAP-LIRE allow p=0 ; R-RAP-DENY-HC deny p=5 → deny gagne (0<5)]
            [Vérifie : resolve-attr extrait confidentialite depuis la map retournée par le REST PIP]"
    (is (false? (e2e "001" "Rapport" "RAP-003" "lire")))))

(deftest ^:integration rapport-hc-deny-beats-allow-dpo
  (testing "Sophie (DPO) lire RAP-003 (hautement-confidentielle) → DENY
            [même DPO ne peut pas lire HC : deny priorité 5 > allow priorité 0]"
    (is (false? (e2e "004" "Rapport" "RAP-003" "lire")))))

(deftest ^:integration rapport-hc-deny-beats-allow-legal
  (testing "Marc (legal-counsel) lire RAP-003 (hautement-confidentielle) → DENY"
    (is (false? (e2e "005" "Rapport" "RAP-003" "lire")))))

;; Tests opération modifier
(deftest ^:integration rapport-dpo-can-modifier
  (testing "Sophie (DPO) modifier RAP-001 → ALLOW [R-RAP-MOD : role == DPO]"
    (is (true? (e2e "004" "Rapport" "RAP-001" "modifier")))))

(deftest ^:integration rapport-chef-cannot-modifier
  (testing "Paul (chef_de_service) modifier RAP-001 → DENY [seul DPO peut modifier]"
    (is (false? (e2e "001" "Rapport" "RAP-001" "modifier")))))

(deftest ^:integration rapport-agent-cannot-modifier
  (testing "John (agent) modifier RAP-001 → DENY"
    (is (false? (e2e "002" "Rapport" "RAP-001" "modifier")))))
