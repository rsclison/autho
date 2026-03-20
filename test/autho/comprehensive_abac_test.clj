(ns autho.comprehensive-abac-test
  "Programme de test complet pour le moteur ABAC Autho.
   Couvre :
     - Tous les opérateurs attfun  : =  diff  <  >  <=  >=  in  notin  date>
     - PIP-LDAP   : enrichissement sujet via prp/personSingleton + fillPerson
     - PIP-Kafka  : attributs ressource via kafka-pip-unified (DossierMedical, Ordonnance)
     - PIP-Rest   : attributs ressource via callPip :rest mocké (RapportActivite)
     - Stratégie  : almost_one_allow_no_deny avec règles allow/deny en concurrence
     - 3 classes  : DossierMedical, Ordonnance, RapportActivite

   Architecture des tests :
     Section A — Unitaire : evaluateRule avec requêtes pré-enrichies (pas de PIP)
     Section B — PIP-LDAP : enrichissement sujet depuis personSingleton
     Section C — PIP-Kafka : attributs ressource via kafka-pip-unified mocké
     Section D — PIP-Rest  : attributs ressource via callPip :rest mocké
     Section E — End-to-end : evalRequest combinant tous les PIPs"
  (:require [clojure.test    :refer :all]
            [clojure.edn     :as edn]
            [clojure.data.json :as json]
            [autho.jsonrule  :as rule]
            [autho.pdp       :as pdp]
            [autho.prp       :as prp]
            [autho.attfun    :as attfun]
            [autho.pip       :as pip]
            [autho.kafka-pip-unified :as kpu]
            [autho.local-cache :as local-cache]))

;; =============================================================================
;; A. DONNÉES DE TEST
;; =============================================================================

;; ---------------------------------------------------------------------------
;; A.1 Sujets — simulés via LDAP (prp/personSingleton)
;; ---------------------------------------------------------------------------
;; clearance stocké en string car attfun/>= appelle edn/read-string dessus.

(def test-persons
  [{:id "DR-001"  :uid "DR-001"  :role "medecin-chef"       :service "cardiologie"
    :clearance "5" :statut "actif"    :grade "senior"  :service-code "CARD"}
   {:id "INF-001" :uid "INF-001" :role "infirmier"           :service "cardiologie"
    :clearance "2" :statut "actif"    :grade "junior"  :service-code "CARD"}
   {:id "DR-002"  :uid "DR-002"  :role "medecin-chef"        :service "pediatrie"
    :clearance "5" :statut "actif"    :grade "senior"  :service-code "PED"}
   {:id "MG-001"  :uid "MG-001"  :role "medecin-generaliste" :service "pediatrie"
    :clearance "3" :statut "actif"    :grade "junior"  :service-code "PED"}
   {:id "SUS-001" :uid "SUS-001" :role "infirmier"           :service "cardiologie"
    :clearance "2" :statut "suspendu" :grade "junior"  :service-code "CARD"}
   {:id "EXT-001" :uid "EXT-001" :role "externe"             :service "cardiologie"
    :clearance "1" :statut "actif"    :grade "stagiaire" :service-code "CARD"}
   {:id "ADM-001" :uid "ADM-001" :role "administrateur"      :service "admin"
    :clearance "3" :statut "actif"    :grade "senior"  :service-code "ADM"}])

;; ---------------------------------------------------------------------------
;; A.2 Ressources Kafka — DossierMedical
;; ---------------------------------------------------------------------------
(def kafka-dossiers
  {"DM-001" {:id "DM-001" :service "cardiologie" :niveau-requis "2" :statut "ouvert"  :age-patient "45"}
   "DM-002" {:id "DM-002" :service "cardiologie" :niveau-requis "4" :statut "ouvert"  :age-patient "12"}
   "DM-003" {:id "DM-003" :service "pediatrie"   :niveau-requis "2" :statut "archive" :age-patient "7"}
   "DM-004" {:id "DM-004" :service "cardiologie" :niveau-requis "3" :statut "ouvert"  :age-patient "63"}})

;; ---------------------------------------------------------------------------
;; A.3 Ressources Kafka — Ordonnance
;; ---------------------------------------------------------------------------
(def kafka-ordonnances
  {"ORD-001" {:id "ORD-001" :service "cardiologie" :roles-autorises "medecin-chef,medecin-generaliste"
              :date-fin-validite "2026-12-31"}
   "ORD-002" {:id "ORD-002" :service "cardiologie" :roles-autorises "medecin-chef"
              :date-fin-validite "2025-01-01"}   ; expiré avant 2026-01-01
   "ORD-003" {:id "ORD-003" :service "pediatrie"   :roles-autorises "medecin-chef,infirmier"
              :date-fin-validite "2027-06-30"}
   "ORD-004" {:id "ORD-004" :service "pediatrie"   :roles-autorises "infirmier,externe"
              :date-fin-validite "2026-06-15"}})

;; ---------------------------------------------------------------------------
;; A.4 Ressources REST — RapportActivite
;; ---------------------------------------------------------------------------
(def rest-rapports
  {"RAP-001" {:id "RAP-001" :departement "admin"       :classification "3" :annee "2025" :type "bilan"}
   "RAP-002" {:id "RAP-002" :departement "cardiologie" :classification "5" :annee "2025" :type "activite"}
   "RAP-003" {:id "RAP-003" :departement "pediatrie"   :classification "2" :annee "2025" :type "activite"}})

;; ---------------------------------------------------------------------------
;; A.5 Politiques — lues depuis EDN (opérateurs = symboles, pas fonctions)
;; ---------------------------------------------------------------------------

(def policy-dossier-medical
  (edn/read-string
   "{:rules
     [{:name \"R-DM-01\" :priority 0 :operation \"lire\" :effect \"allow\"
       :conditions [[= [Person $s service] [DossierMedical $r service]]
                    [>= [Person $s clearance] [DossierMedical $r niveau-requis]]
                    [diff [Person $s statut] \"suspendu\"]]}
      {:name \"R-DM-02\" :priority 1 :operation \"lire\" :effect \"allow\"
       :conditions [[= [Person $s role] \"medecin-chef\"]
                    [= [DossierMedical $r statut] \"ouvert\"]]}
      {:name \"R-DM-03\" :priority 10 :operation \"lire\" :effect \"deny\"
       :conditions [[= [DossierMedical $r statut] \"archive\"]]}
      {:name \"R-DM-04\" :priority 0 :operation \"modifier\" :effect \"allow\"
       :conditions [[= [Person $s role] \"medecin-chef\"]
                    [= [Person $s service] [DossierMedical $r service]]]}
      {:name \"R-DM-05\" :priority 5 :operation \"lire\" :effect \"deny\"
       :conditions [[= [Person $s grade] \"stagiaire\"]
                    [< [Person $s clearance] \"3\"]]}]
    :strategy :almost_one_allow_no_deny}"))

(def policy-ordonnance
  (edn/read-string
   "{:rules
     [{:name \"R-ORD-01\" :priority 0 :operation \"signer\" :effect \"allow\"
       :conditions [[in [Person $s role] [Ordonnance $r roles-autorises]]
                    [= [Person $s service] [Ordonnance $r service]]
                    [notin [Person $s statut] \"suspendu,expire\"]]}
      {:name \"R-ORD-02\" :priority 10 :operation \"signer\" :effect \"deny\"
       :conditions [[date> \"2026-01-01\" [Ordonnance $r date-fin-validite]]]}]
    :strategy :almost_one_allow_no_deny}"))

(def policy-rapport
  (edn/read-string
   "{:rules
     [{:name \"R-RAP-01\" :priority 0 :operation \"lire\" :effect \"allow\"
       :conditions [[= [Person $s service] [RapportActivite $r departement]]
                    [>= [Person $s clearance] [RapportActivite $r classification]]]}
      {:name \"R-RAP-02\" :priority 10 :operation \"lire\" :effect \"deny\"
       :conditions [[= [Person $s statut] \"suspendu\"]]}
      {:name \"R-RAP-03\" :priority 5 :operation \"lire\" :effect \"deny\"
       :conditions [[< [Person $s clearance] [RapportActivite $r classification]]]}]
    :strategy :almost_one_allow_no_deny}"))

;; Index rapide person-id → attributs
(defn- person-by-id [id]
  (first (filter #(= (:id %) id) test-persons)))

;; ---------------------------------------------------------------------------
;; A.6 PIPs de test configurés dans prp/pips
;; ---------------------------------------------------------------------------
(def test-pips
  [{:type :kafka-pip-unified
    :kafka-topic "test-topic"
    :classes ["DossierMedical" "Ordonnance"]}
   {:class "RapportActivite"
    :pip {:type :rest
          :url  "http://test-api/rapports"
          :verb "get"}}])

;; Mock de kafka-pip-unified/query-pip retournant nos données de test
(defn kafka-query-mock [class-name object-id]
  (case class-name
    "DossierMedical" (get kafka-dossiers object-id)
    "Ordonnance"     (get kafka-ordonnances object-id)
    nil))

;; Mock de pip/callPip :rest — retourne l'attribut demandé depuis rest-rapports
;; (dans callPip :rest la réponse brute serait le corps JSON entier ;
;;  ici on extrait l'attribut au bon niveau, comme le ferait un vrai client HTTP
;;  couplé à une réponse {"departement":"admin",...})
(defn rest-callpip-mock [decl att obj]
  (let [rapport (get rest-rapports (str (:id obj)))]
    (get rapport (keyword att))))

;; Fixture de setup/teardown des atomes partagés
(defn with-test-pips [f]
  (let [saved-pips     @prp/pips
        saved-persons  @prp/personSingleton]
    (reset! prp/pips test-pips)
    (reset! prp/personSingleton test-persons)
    (try (f)
         (finally
           (reset! prp/pips saved-pips)
           (reset! prp/personSingleton saved-persons)))))

(use-fixtures :each with-test-pips)

;; =============================================================================
;; SECTION A — Tests unitaires des opérateurs (evaluateRule, requête pré-enrichie)
;; Aucun PIP sollicité : les attributs sont déjà dans la requête.
;; =============================================================================

(deftest operateur-egalite-test
  (testing "Opérateur = : égalité de valeurs scalaires et d'attributs"
    (let [rule    (edn/read-string
                   "{:name \"R-test\" :effect \"allow\"
                     :conditions [[= [Person $s role] \"admin\"]
                                  [= [Doc $s dept] [Doc $r dept]]]}")
          req-ok  {:subject  {:role "admin" :dept "IT"}
                   :resource {:dept "IT"}}
          req-ko  {:subject  {:role "user"  :dept "IT"}
                   :resource {:dept "IT"}}]
      (is (true?  (:value (rule/evaluateRule rule req-ok))))
      (is (false? (:value (rule/evaluateRule rule req-ko)))))))

(deftest operateur-diff-test
  (testing "Opérateur diff : inégalité (≠)"
    (let [rule (edn/read-string
                "{:name \"R\" :effect \"allow\"
                  :conditions [[diff [Person $s statut] \"suspendu\"]
                               [diff [Person $s statut] \"expire\"]]}")
          actif  {:subject {:statut "actif"}    :resource {}}
          suspendu {:subject {:statut "suspendu"} :resource {}}]
      (is (true?  (:value (rule/evaluateRule rule actif))))
      (is (false? (:value (rule/evaluateRule rule suspendu)))))))

(deftest operateur-inferieur-strict-test
  (testing "Opérateur < : strictement inférieur (numérique)"
    (let [rule (edn/read-string
                "{:name \"R\" :effect \"deny\"
                  :conditions [[< [Person $s clearance] \"3\"]]}")
          cl1 {:subject {:clearance "1"} :resource {}}
          cl3 {:subject {:clearance "3"} :resource {}}
          cl5 {:subject {:clearance "5"} :resource {}}]
      (is (true?  (:value (rule/evaluateRule rule cl1))))   ; 1 < 3 → deny match
      (is (false? (:value (rule/evaluateRule rule cl3))))   ; 3 < 3 → false
      (is (false? (:value (rule/evaluateRule rule cl5)))))) ; 5 < 3 → false
  (testing "Opérateur < : comparaison attribut vs attribut"
    (let [rule (edn/read-string
                "{:name \"R\" :effect \"deny\"
                  :conditions [[< [Person $s clearance] [Res $r niveau-requis]]]}")
          cl2-req4 {:subject {:clearance "2"} :resource {:niveau-requis "4"}}
          cl5-req4 {:subject {:clearance "5"} :resource {:niveau-requis "4"}}]
      (is (true?  (:value (rule/evaluateRule rule cl2-req4))))
      (is (false? (:value (rule/evaluateRule rule cl5-req4)))))))

(deftest operateur-superieur-strict-test
  (testing "Opérateur > : strictement supérieur (numérique)"
    (let [rule (edn/read-string
                "{:name \"R\" :effect \"allow\"
                  :conditions [[> [Person $s clearance] \"3\"]]}")
          cl5 {:subject {:clearance "5"} :resource {}}
          cl3 {:subject {:clearance "3"} :resource {}}
          cl1 {:subject {:clearance "1"} :resource {}}]
      (is (true?  (:value (rule/evaluateRule rule cl5))))   ; 5 > 3 → allow
      (is (false? (:value (rule/evaluateRule rule cl3))))   ; 3 > 3 → false
      (is (false? (:value (rule/evaluateRule rule cl1)))))) ; 1 > 3 → false
  (testing "Opérateur > : attribut vs attribut"
    (let [rule (edn/read-string
                "{:name \"R\" :effect \"allow\"
                  :conditions [[> [Person $s seuil] [Doc $r montant]]]}")
          ok  {:subject {:seuil "50000"} :resource {:montant "30000"}}
          ko  {:subject {:seuil "20000"} :resource {:montant "30000"}}]
      (is (true?  (:value (rule/evaluateRule rule ok))))
      (is (false? (:value (rule/evaluateRule rule ko)))))))

(deftest operateur-inferieur-egal-test
  (testing "Opérateur <= : inférieur ou égal"
    (let [rule (edn/read-string
                "{:name \"R\" :effect \"allow\"
                  :conditions [[<= [Person $s age] \"65\"]]}")
          a40 {:subject {:age "40"} :resource {}}
          a65 {:subject {:age "65"} :resource {}}
          a70 {:subject {:age "70"} :resource {}}]
      (is (true?  (:value (rule/evaluateRule rule a40))))   ; 40 <= 65
      (is (true?  (:value (rule/evaluateRule rule a65))))   ; 65 <= 65
      (is (false? (:value (rule/evaluateRule rule a70))))))) ; 70 <= 65 → false

(deftest operateur-superieur-egal-test
  (testing "Opérateur >= : supérieur ou égal"
    (let [rule (edn/read-string
                "{:name \"R\" :effect \"allow\"
                  :conditions [[>= [Person $s clearance] [Res $r niveau-requis]]]}")
          ok-egal  {:subject {:clearance "3"} :resource {:niveau-requis "3"}}
          ok-sup   {:subject {:clearance "5"} :resource {:niveau-requis "3"}}
          ko       {:subject {:clearance "2"} :resource {:niveau-requis "3"}}]
      (is (true?  (:value (rule/evaluateRule rule ok-egal))))
      (is (true?  (:value (rule/evaluateRule rule ok-sup))))
      (is (false? (:value (rule/evaluateRule rule ko)))))))

(deftest operateur-in-test
  (testing "Opérateur in : appartenance à une liste séparée par virgules"
    (let [rule (edn/read-string
                "{:name \"R\" :effect \"allow\"
                  :conditions [[in [Person $s role] [Ord $r roles-autorises]]]}")
          chef-ok  {:subject {:role "medecin-chef"}       :resource {:roles-autorises "medecin-chef,infirmier"}}
          inf-ok   {:subject {:role "infirmier"}          :resource {:roles-autorises "medecin-chef,infirmier"}}
          ext-ko   {:subject {:role "externe"}            :resource {:roles-autorises "medecin-chef,infirmier"}}]
      (is (true?  (:value (rule/evaluateRule rule chef-ok))))
      (is (true?  (:value (rule/evaluateRule rule inf-ok))))
      (is (false? (:value (rule/evaluateRule rule ext-ko))))))
  (testing "Opérateur in : valeur littérale"
    (let [rule (edn/read-string
                "{:name \"R\" :effect \"allow\"
                  :conditions [[in [Person $s role] \"admin,superadmin,root\"]]}")
          admin    {:subject {:role "admin"}     :resource {}}
          regular  {:subject {:role "comptable"} :resource {}}]
      (is (true?  (:value (rule/evaluateRule rule admin))))
      (is (false? (:value (rule/evaluateRule rule regular)))))))

(deftest operateur-notin-test
  (testing "Opérateur notin : non-appartenance"
    (let [rule (edn/read-string
                "{:name \"R\" :effect \"allow\"
                  :conditions [[notin [Person $s statut] \"suspendu,expire,bloque\"]]}")
          actif    {:subject {:statut "actif"}    :resource {}}
          suspendu {:subject {:statut "suspendu"} :resource {}}
          expire   {:subject {:statut "expire"}   :resource {}}]
      (is (true?  (:value (rule/evaluateRule rule actif))))
      (is (false? (:value (rule/evaluateRule rule suspendu))))
      (is (false? (:value (rule/evaluateRule rule expire)))))))

(deftest operateur-date-superieur-test
  (testing "Opérateur date> : comparaison de dates ISO 8601"
    ;; date> d1 d2 → true si d1 est APRÈS d2
    (let [rule (edn/read-string
                "{:name \"R\" :effect \"deny\"
                  :conditions [[date> \"2026-01-01\" [Doc $r date-fin-validite]]]}")
          expire  {:subject {} :resource {:date-fin-validite "2025-01-01"}}   ; 2026 > 2025 → deny
          valide  {:subject {} :resource {:date-fin-validite "2026-12-31"}}   ; 2026 > 2026-12-31? non
          futur   {:subject {} :resource {:date-fin-validite "2028-06-01"}}]  ; 2026 > 2028? non
      (is (true?  (:value (rule/evaluateRule rule expire))))   ; expiré
      (is (false? (:value (rule/evaluateRule rule valide))))   ; encore valide
      (is (false? (:value (rule/evaluateRule rule futur))))))  ; loin dans le futur
  (testing "Opérateur date> : comparaison attribut vs attribut"
    (let [rule (edn/read-string
                "{:name \"R\" :effect \"allow\"
                  :conditions [[date> [Person $s date-certification] \"2020-01-01\"]]}")
          recent {:subject {:date-certification "2023-06-15"} :resource {}}  ; certif récente
          vieille {:subject {:date-certification "2019-12-31"} :resource {}}] ; avant 2020
      (is (true?  (:value (rule/evaluateRule rule recent))))
      (is (false? (:value (rule/evaluateRule rule vieille)))))))

(deftest nil-guard-pip-manquant-test
  (testing "Opérande nil → la clause retourne false (pas de nil=nil=true)"
    ;; L'attribut 'inexistant' n'est pas dans l'objet et aucun PIP ne le connait
    ;; → eval-operand2 retourne nil → eval-clause2 retourne false
    (let [rule (edn/read-string
                "{:name \"R\" :effect \"allow\"
                  :conditions [[= [Person $s attribut-inexistant] \"valeur\"]]}")
          req {:subject {} :resource {}}]
      (is (false? (:value (rule/evaluateRule rule req)))))))

(deftest operation-filtering-test
  (testing "Les règles ne s'appliquent qu'à leur opération"
    (let [rule-lire (edn/read-string
                     "{:name \"R-lire\" :priority 0 :operation \"lire\" :effect \"allow\"
                       :conditions [[= [Person $s role] \"admin\"]]}")
          req {:subject {:role "admin"} :resource {} :operation "modifier"}]
      ;; evaluateRule évalue les conditions mais pas l'opération
      ;; c'est pdp/applicable-rules qui filtre l'opération
      (is (true? (:value (rule/evaluateRule rule-lire req)))))))

(deftest resolve-conflict-allow-no-deny-test
  (testing "Stratégie almost_one_allow_no_deny : allow gagne sans deny"
    (let [policy {:strategy :almost_one_allow_no_deny}
          allow-rules [{:name "R1" :effect "allow" :priority 0}]]
      (is (true? (pdp/resolve-conflict policy allow-rules)))))
  (testing "Stratégie almost_one_allow_no_deny : deny haute priorité bat allow"
    (let [policy {:strategy :almost_one_allow_no_deny}
          mixed  [{:name "R-allow" :effect "allow" :priority 0}
                  {:name "R-deny"  :effect "deny"  :priority 10}]]
      (is (false? (pdp/resolve-conflict policy mixed)))))
  (testing "Stratégie almost_one_allow_no_deny : allow haute priorité bat deny"
    (let [policy {:strategy :almost_one_allow_no_deny}
          mixed  [{:name "R-allow" :effect "allow" :priority 10}
                  {:name "R-deny"  :effect "deny"  :priority 5}]]
      (is (true? (pdp/resolve-conflict policy mixed)))))
  (testing "Aucune règle satisfaite → false"
    (is (false? (pdp/resolve-conflict {:strategy :almost_one_allow_no_deny} [])))))

;; =============================================================================
;; SECTION B — PIP-LDAP : enrichissement sujet via prp/personSingleton
;; fillPerson fusionne les attributs LDAP dans le sujet avant évaluation.
;; =============================================================================

(deftest pip-ldap-enrichissement-sujet-test
  (testing "fillPerson enrichit le sujet depuis personSingleton"
    (let [sujet-brut {:id "DR-001" :class "Person"}
          enrichi    (attfun/fillPerson sujet-brut)
          p          (person-by-id "DR-001")]
      (is (= (:role p)      (:role enrichi)))
      (is (= (:service p)   (:service enrichi)))
      (is (= (:clearance p) (:clearance enrichi)))))
  (testing "fillPerson laisse le sujet inchangé si id inconnu"
    (let [inconnu {:id "INCONNU" :class "Person"}]
      (is (= inconnu (attfun/fillPerson inconnu))))))

(deftest pip-ldap-medecin-chef-dossier-ouvert-test
  (testing "R-DM-02 : médecin-chef accède à tout dossier ouvert (PIP-LDAP)"
    ;; Sujet enrichi par fillPerson, ressource avec attributs inline (simule Kafka)
    (let [r-dm02 (second (:rules policy-dossier-medical))  ; R-DM-02
          req    {:subject  (attfun/fillPerson {:id "DR-001" :class "Person"})
                  :resource {:service "cardiologie" :niveau-requis "2" :statut "ouvert"}}]
      (is (true? (:value (rule/evaluateRule r-dm02 req)))))))

(deftest pip-ldap-infirmier-acces-meme-service-test
  (testing "R-DM-01 : infirmier accède dossier si même service et clearance ≥ niveau requis"
    (let [r-dm01 (first (:rules policy-dossier-medical))
          inf    (attfun/fillPerson {:id "INF-001" :class "Person"})
          req-ok {:subject  inf
                  :resource {:service "cardiologie" :niveau-requis "2"
                              :statut "ouvert"}}
          req-ko {:subject  inf
                  :resource {:service "cardiologie" :niveau-requis "4"
                              :statut "ouvert"}}]   ; clearance 2 < 4
      (is (true?  (:value (rule/evaluateRule r-dm01 req-ok))))
      (is (false? (:value (rule/evaluateRule r-dm01 req-ko)))))))

(deftest pip-ldap-suspendu-bloque-test
  (testing "R-DM-01 : sujet suspendu → condition [diff statut suspendu] échoue"
    (let [r-dm01   (first (:rules policy-dossier-medical))
          suspendu (attfun/fillPerson {:id "SUS-001" :class "Person"})
          req {:subject  suspendu
               :resource {:service "cardiologie" :niveau-requis "2" :statut "ouvert"}}]
      (is (false? (:value (rule/evaluateRule r-dm01 req)))))))

;; =============================================================================
;; SECTION C — PIP-Kafka : attributs ressource via kafka-pip-unified mocké
;; =============================================================================

;; Helpers : construit une requête avec sujet LDAP + attributs Kafka dans la ressource
(defn- make-req
  "Construit une requête enrichie : subject depuis test-persons, resource depuis les maps Kafka."
  [subject-id resource]
  {:subject  (attfun/fillPerson {:id subject-id :class "Person"})
   :resource resource})

(deftest pip-kafka-dossier-medical-tests
  ;; Simule que les attributs ressource ont été récupérés via Kafka
  ;; (dans une vraie exécution, resolve-attr → findAndCallPip → callPip :kafka-pip-unified)
  (with-redefs [kpu/query-pip kafka-query-mock]

    (testing "T1 — DR-001 / DM-001 / lire → ALLOW (R-DM-01 et R-DM-02 satisfaits)"
      (let [dm   (kafka-query-mock "DossierMedical" "DM-001")
            req  (make-req "DR-001" dm)
            r01  (nth (:rules policy-dossier-medical) 0)
            r02  (nth (:rules policy-dossier-medical) 1)]
        (is (true?  (:value (rule/evaluateRule r01 req))))  ; même service, 5≥2, non-suspendu
        (is (true?  (:value (rule/evaluateRule r02 req))))  ; chef + ouvert
        (is (true?  (pdp/resolve-conflict
                     policy-dossier-medical
                     (filter #(:value (rule/evaluateRule % req)) (:rules policy-dossier-medical)))))))

    (testing "T2 — INF-001 / DM-001 / lire → ALLOW (R-DM-01 satisfait, clearance 2≥2)"
      (let [dm  (kafka-query-mock "DossierMedical" "DM-001")
            req (make-req "INF-001" dm)]
        (is (true? (pdp/resolve-conflict
                    policy-dossier-medical
                    (filter #(:value (rule/evaluateRule % {:subject (:subject req)
                                                           :resource dm}))
                            (filter #(or (nil? (:operation %)) (= "lire" (:operation %)))
                                    (:rules policy-dossier-medical))))))))

    (testing "T3 — INF-001 / DM-002 / lire → DENY (clearance 2 < niveau-requis 4)"
      (let [dm  (kafka-query-mock "DossierMedical" "DM-002")
            req {:subject (attfun/fillPerson {:id "INF-001" :class "Person"})
                 :resource dm}
            lire-rules (filter #(or (nil? (:operation %)) (= "lire" (:operation %)))
                                (:rules policy-dossier-medical))
            matched (filter #(:value (rule/evaluateRule % req)) lire-rules)]
        (is (empty? (filter #(= "allow" (:effect %)) matched)))))

    (testing "T4 — DR-001 / DM-003 (archive) / lire → DENY (R-DM-03 priorité 10 gagne)"
      (let [dm  (kafka-query-mock "DossierMedical" "DM-003")
            req {:subject (attfun/fillPerson {:id "DR-001" :class "Person"})
                 :resource dm}
            lire-rules (filter #(or (nil? (:operation %)) (= "lire" (:operation %)))
                                (:rules policy-dossier-medical))
            matched (filter #(:value (rule/evaluateRule % req)) lire-rules)]
        (is (false? (pdp/resolve-conflict policy-dossier-medical matched)))))

    (testing "T5 — DR-002 / DM-003 (archive) / lire → DENY (R-DM-01 p=0 < R-DM-03 p=10)"
      ;; DR-002 est pédiatre (même service que DM-003) mais DM-03 est archivé
      ;; R-DM-01 match (même service, clearance ok) mais R-DM-03 match (archive) avec p=10
      (let [dm  (kafka-query-mock "DossierMedical" "DM-003")
            req {:subject (attfun/fillPerson {:id "DR-002" :class "Person"})
                 :resource dm}
            lire-rules (filter #(or (nil? (:operation %)) (= "lire" (:operation %)))
                                (:rules policy-dossier-medical))
            matched (filter #(:value (rule/evaluateRule % req)) lire-rules)]
        (is (false? (pdp/resolve-conflict policy-dossier-medical matched)))))

    (testing "T6 — SUS-001 / DM-001 / lire → DENY (diff statut suspendu échoue)"
      (let [dm  (kafka-query-mock "DossierMedical" "DM-001")
            req {:subject (attfun/fillPerson {:id "SUS-001" :class "Person"})
                 :resource dm}
            r01 (nth (:rules policy-dossier-medical) 0)]
        (is (false? (:value (rule/evaluateRule r01 req))))))    ; diff "suspendu" "suspendu" = false

    (testing "T7 — EXT-001 (stagiaire, cl=1) / DM-001 / lire → DENY (R-DM-05 : grade=stagiaire, cl<3)"
      (let [dm  (kafka-query-mock "DossierMedical" "DM-001")
            req {:subject (attfun/fillPerson {:id "EXT-001" :class "Person"})
                 :resource dm}
            r05 (nth (:rules policy-dossier-medical) 4)]
        (is (true? (:value (rule/evaluateRule r05 req))))))    ; R-DM-05 deny matche

    (testing "T8 — DR-001 / DM-001 / modifier → ALLOW (R-DM-04 : chef même service)"
      (let [dm  (kafka-query-mock "DossierMedical" "DM-001")
            req {:subject (attfun/fillPerson {:id "DR-001" :class "Person"})
                 :resource dm}
            r04 (nth (:rules policy-dossier-medical) 3)]
        (is (true? (:value (rule/evaluateRule r04 req))))))

    (testing "T9 — INF-001 / DM-001 / modifier → DENY (R-DM-04 : role=infirmier ≠ chef)"
      (let [dm  (kafka-query-mock "DossierMedical" "DM-001")
            req {:subject (attfun/fillPerson {:id "INF-001" :class "Person"})
                 :resource dm}
            r04 (nth (:rules policy-dossier-medical) 3)]
        (is (false? (:value (rule/evaluateRule r04 req))))))

    (testing "T10 — DR-002 / DM-001 / modifier → DENY (R-DM-04 : services différents)"
      (let [dm  (kafka-query-mock "DossierMedical" "DM-001")  ; cardiologie
            req {:subject (attfun/fillPerson {:id "DR-002" :class "Person"})  ; pediatrie
                 :resource dm}
            r04 (nth (:rules policy-dossier-medical) 3)]
        (is (false? (:value (rule/evaluateRule r04 req))))))))  ; pediatrie ≠ cardiologie

(deftest pip-kafka-ordonnance-tests
  (with-redefs [kpu/query-pip kafka-query-mock]

    (testing "T11 — DR-001 (chef, cardio, actif) / ORD-001 (valid) / signer → ALLOW"
      ;; R-ORD-01: in chef ∈ {chef,gen} ✓ ; même service ✓ ; notin actif ∉ {sus,exp} ✓
      ;; R-ORD-02: date>(2026-01-01, 2026-12-31) = 2026-01-01 après 2026-12-31 ? NON → deny ne matche pas
      (let [ord (kafka-query-mock "Ordonnance" "ORD-001")
            req {:subject (attfun/fillPerson {:id "DR-001" :class "Person"})
                 :resource ord}
            matched (filter #(:value (rule/evaluateRule % req)) (:rules policy-ordonnance))]
        (is (true? (pdp/resolve-conflict policy-ordonnance matched)))))

    (testing "T12 — INF-001 (infirmier, cardio) / ORD-001 / signer → DENY"
      ;; R-ORD-01: in infirmier ∈ {chef,gen} → FALSE
      ;; R-ORD-02: ordonnance non expirée → FALSE
      (let [ord (kafka-query-mock "Ordonnance" "ORD-001")
            req {:subject (attfun/fillPerson {:id "INF-001" :class "Person"})
                 :resource ord}
            matched (filter #(:value (rule/evaluateRule % req)) (:rules policy-ordonnance))]
        (is (empty? matched))
        (is (false? (pdp/resolve-conflict policy-ordonnance matched)))))

    (testing "T13 — DR-001 / ORD-002 (expiré 2025-01-01) / signer → DENY (deny p=10 l'emporte)"
      ;; R-ORD-01 matche (chef, cardio, actif) → allow p=0
      ;; R-ORD-02: date>(2026-01-01, 2025-01-01) = OUI → deny p=10
      ;; allow p=0 < deny p=10 → DENY
      (let [ord (kafka-query-mock "Ordonnance" "ORD-002")
            req {:subject (attfun/fillPerson {:id "DR-001" :class "Person"})
                 :resource ord}
            matched (filter #(:value (rule/evaluateRule % req)) (:rules policy-ordonnance))]
        (is (some #(= "allow" (:effect %)) matched))           ; allow matche aussi
        (is (some #(= "deny"  (:effect %)) matched))           ; deny matche
        (is (false? (pdp/resolve-conflict policy-ordonnance matched)))))  ; deny priorité

    (testing "T14 — SUS-001 (suspendu) / ORD-001 / signer → DENY (notin échoue)"
      ;; R-ORD-01: notin "suspendu" ∈ "suspendu,expire" → FALSE (il est dedans)
      (let [ord (kafka-query-mock "Ordonnance" "ORD-001")
            req {:subject (attfun/fillPerson {:id "SUS-001" :class "Person"})
                 :resource ord}
            r01 (first (:rules policy-ordonnance))]
        (is (false? (:value (rule/evaluateRule r01 req))))))

    (testing "T15 — DR-002 (chef, pediatrie) / ORD-003 (pediatrie, valid) / signer → ALLOW"
      (let [ord (kafka-query-mock "Ordonnance" "ORD-003")
            req {:subject (attfun/fillPerson {:id "DR-002" :class "Person"})
                 :resource ord}
            matched (filter #(:value (rule/evaluateRule % req)) (:rules policy-ordonnance))]
        (is (true? (pdp/resolve-conflict policy-ordonnance matched)))))

    (testing "T16 — MG-001 (generaliste, pediatrie) / ORD-001 (cardiologie) / signer → DENY"
      ;; Service pediatrie ≠ cardiologie → R-ORD-01 échoue
      (let [ord (kafka-query-mock "Ordonnance" "ORD-001")
            req {:subject (attfun/fillPerson {:id "MG-001" :class "Person"})
                 :resource ord}
            r01 (first (:rules policy-ordonnance))]
        (is (false? (:value (rule/evaluateRule r01 req))))))

    (testing "T-Ord-Extra — in avec opérateur sur valeur littérale (rôles en set)"
      ;; MG-001 (generaliste) / ORD-004 (roles=infirmier,externe) / signer → DENY
      (let [ord (kafka-query-mock "Ordonnance" "ORD-004")  ; infirmier,externe
            req {:subject (attfun/fillPerson {:id "MG-001" :class "Person"})  ; generaliste
                 :resource ord}
            r01 (first (:rules policy-ordonnance))]
        (is (false? (:value (rule/evaluateRule r01 req))))))))  ; generaliste ∉ {infirmier,externe}

;; =============================================================================
;; SECTION D — PIP-REST : attributs ressource via callPip :rest mocké
;; Architecture : pip/callPip dispatche vers :rest ; mock intercepte au niveau HTTP
;; =============================================================================

;; Pour tester callPip :rest au niveau bas, on mocke le client HTTP.
;; clj-http avec :as :json parse le corps automatiquement → body est une map, pas une string.
(defn- mock-http-get [url opts]
  ;; URL attendue : "http://test-api/rapports/<id>"
  (let [id   (last (clojure.string/split url #"/"))
        data (get rest-rapports id)]
    {:status 200
     ;; Simule clj-http :as :json : body = map Clojure avec clés string (comme Jackson)
     :body   (when data (into {} (map (fn [[k v]] [(name k) v]) data)))}))

(deftest pip-rest-callpip-niveau-bas-test
  (testing "callPip :rest : appel HTTP GET et retour du corps JSON"
    (with-redefs [clj-http.client/get mock-http-get]
      (let [pip-decl {:pip {:type :rest :url "http://test-api/rapports" :verb "get"}}
            obj      {:id "RAP-001" :class "RapportActivite"}
            result   (pip/callPip pip-decl "departement" obj)]
        ;; callPip :rest retourne le corps entier (pas seulement l'attribut)
        (is (map? result))
        (is (= "admin" (get result "departement"))))))
  (testing "callPip :rest : 404 retourne une map d'erreur"
    (with-redefs [clj-http.client/get (fn [url opts]
                                        {:status 404 :body "{}"})]
      (let [pip-decl {:pip {:type :rest :url "http://test-api/rapports" :verb "get"}}
            result   (pip/callPip pip-decl "departement" {:id "INCONNU"})]
        (is (map? result))
        (is (= "object not found" (:error result)))))))

(deftest pip-rest-evaluation-regles-test
  "Teste les règles RapportActivite avec les attributs venant de callPip :rest.
   On mocke pip/callPip pour qu'il extrait l'attribut directement depuis rest-rapports
   (simule callPip :rest + extraction attribut par le client)."
  (with-redefs [pip/callPip (fn [decl att obj]
                               (let [class (:class obj)]
                                 (when (= class "RapportActivite")
                                   (rest-callpip-mock decl att obj))))]
    (testing "T17 — ADM-001 (admin, cl=3) / RAP-001 (admin, classif=3) / lire → ALLOW"
      ;; R-RAP-01 : = service admin ✓ ; >= 3 >= 3 ✓ → allow p=0
      ;; R-RAP-03 : < 3 < 3 ? NON → deny ne matche pas
      (let [req {:subject (attfun/fillPerson {:id "ADM-001" :class "Person"})
                 :resource {:id "RAP-001" :class "RapportActivite"}}
            matched (filter #(:value (rule/evaluateRule % req)) (:rules policy-rapport))]
        (is (true? (pdp/resolve-conflict policy-rapport matched)))))

    (testing "T18 — ADM-001 (admin, cl=3) / RAP-002 (cardiologie, classif=5) / lire → DENY"
      ;; R-RAP-01 : admin ≠ cardiologie → FALSE
      (let [req {:subject (attfun/fillPerson {:id "ADM-001" :class "Person"})
                 :resource {:id "RAP-002" :class "RapportActivite"}}
            matched (filter #(:value (rule/evaluateRule % req)) (:rules policy-rapport))]
        (is (empty? (filter #(= "allow" (:effect %)) matched)))))

    (testing "T19 — DR-001 (cardio, cl=5) / RAP-002 (cardio, classif=5) / lire → ALLOW"
      (let [req {:subject (attfun/fillPerson {:id "DR-001" :class "Person"})
                 :resource {:id "RAP-002" :class "RapportActivite"}}
            matched (filter #(:value (rule/evaluateRule % req)) (:rules policy-rapport))]
        (is (true? (pdp/resolve-conflict policy-rapport matched)))))

    (testing "T20 — INF-001 (cardio, cl=2) / RAP-002 (cardio, classif=5) / lire → DENY"
      ;; R-RAP-01 : >= 2 >= 5 ? NON → FALSE
      ;; R-RAP-03 : < 2 < 5 ? OUI → deny p=5
      (let [req {:subject (attfun/fillPerson {:id "INF-001" :class "Person"})
                 :resource {:id "RAP-002" :class "RapportActivite"}}
            r03 (nth (:rules policy-rapport) 2)]
        (is (true? (:value (rule/evaluateRule r03 req))))))

    (testing "T21 — SUS-001 (suspendu) / RAP-001 (admin) / lire → DENY (R-RAP-02 : statut=suspendu)"
      (let [req {:subject (attfun/fillPerson {:id "SUS-001" :class "Person"})
                 :resource {:id "RAP-001" :class "RapportActivite"}}
            r02 (nth (:rules policy-rapport) 1)]
        (is (true? (:value (rule/evaluateRule r02 req))))))))  ; deny matche

;; =============================================================================
;; SECTION E — Tests end-to-end via pdp/evalRequest (tous PIPs combinés)
;; On mock getGlobalPolicy, kafka-pip-unified/query-pip et pip/callPip
;; pour simuler le flux complet de pdp/evalRequest.
;; =============================================================================

(def policies-map
  {"DossierMedical"  policy-dossier-medical
   "Ordonnance"      policy-ordonnance
   "RapportActivite" policy-rapport})

(defn- e2e-eval
  "Évalue une requête complète via pdp/evalRequest avec tous les PIPs mockés."
  [subject-id resource-class resource-id operation]
  (with-redefs [prp/getGlobalPolicy (fn [cls] (get policies-map cls))
                kpu/query-pip       kafka-query-mock
                pip/callPip         (fn [decl att obj]
                                      (let [tp (or (get-in decl [:pip :type]) (:type decl))]
                                        (cond
                                          (= tp :kafka-pip-unified)
                                          (get (kafka-query-mock (:class obj) (str (:id obj)))
                                               (keyword att))
                                          (= tp :rest)
                                          (rest-callpip-mock decl att obj)
                                          :else nil)))]
    (let [request {:subject   {:id subject-id :class "Person"}
                   :resource  {:id resource-id :class resource-class}
                   :operation operation}]
      (pdp/evalRequest request))))

(deftest e2e-dossier-medical-tests
  (testing "E2E-1 — DR-001 / DM-001 / lire → résultat true"
    (let [res (e2e-eval "DR-001" "DossierMedical" "DM-001" "lire")]
      (is (true? (:result res)))))

  (testing "E2E-2 — INF-001 / DM-001 / lire → résultat true (même service, cl=2≥2)"
    (let [res (e2e-eval "INF-001" "DossierMedical" "DM-001" "lire")]
      (is (true? (:result res)))))

  (testing "E2E-3 — INF-001 / DM-002 / lire → résultat false (clearance insuffisant)"
    (let [res (e2e-eval "INF-001" "DossierMedical" "DM-002" "lire")]
      (is (false? (:result res)))))

  (testing "E2E-4 — DR-001 / DM-003 (archive) / lire → résultat false"
    (let [res (e2e-eval "DR-001" "DossierMedical" "DM-003" "lire")]
      (is (false? (:result res)))))

  (testing "E2E-5 — SUS-001 (suspendu) / DM-001 / lire → résultat false"
    (let [res (e2e-eval "SUS-001" "DossierMedical" "DM-001" "lire")]
      (is (false? (:result res)))))

  (testing "E2E-6 — EXT-001 (stagiaire, cl=1) / DM-001 / lire → résultat false"
    (let [res (e2e-eval "EXT-001" "DossierMedical" "DM-001" "lire")]
      (is (false? (:result res)))))

  (testing "E2E-7 — DR-001 / DM-001 / modifier → résultat true (chef même service)"
    (let [res (e2e-eval "DR-001" "DossierMedical" "DM-001" "modifier")]
      (is (true? (:result res)))))

  (testing "E2E-8 — DR-002 / DM-001 / modifier → résultat false (service différent)"
    (let [res (e2e-eval "DR-002" "DossierMedical" "DM-001" "modifier")]
      (is (false? (:result res))))))

(deftest e2e-ordonnance-tests
  (testing "E2E-Ord-1 — DR-001 / ORD-001 (valide) / signer → true"
    (let [res (e2e-eval "DR-001" "Ordonnance" "ORD-001" "signer")]
      (is (true? (:result res)))))

  (testing "E2E-Ord-2 — INF-001 / ORD-001 / signer → false (infirmier ∉ roles autorisés)"
    (let [res (e2e-eval "INF-001" "Ordonnance" "ORD-001" "signer")]
      (is (false? (:result res)))))

  (testing "E2E-Ord-3 — DR-001 / ORD-002 (expiré) / signer → false (deny expire)"
    (let [res (e2e-eval "DR-001" "Ordonnance" "ORD-002" "signer")]
      (is (false? (:result res)))))

  (testing "E2E-Ord-4 — SUS-001 / ORD-001 / signer → false (suspendu)"
    (let [res (e2e-eval "SUS-001" "Ordonnance" "ORD-001" "signer")]
      (is (false? (:result res)))))

  (testing "E2E-Ord-5 — DR-002 / ORD-003 (pediatrie, valide) / signer → true"
    (let [res (e2e-eval "DR-002" "Ordonnance" "ORD-003" "signer")]
      (is (true? (:result res))))))

(deftest e2e-rapport-activite-tests
  (testing "E2E-Rap-1 — ADM-001 (admin, cl=3) / RAP-001 (admin, classif=3) / lire → true"
    (let [res (e2e-eval "ADM-001" "RapportActivite" "RAP-001" "lire")]
      (is (true? (:result res)))))

  (testing "E2E-Rap-2 — ADM-001 / RAP-002 (cardiologie) / lire → false (service différent)"
    (let [res (e2e-eval "ADM-001" "RapportActivite" "RAP-002" "lire")]
      (is (false? (:result res)))))

  (testing "E2E-Rap-3 — DR-001 (cardio, cl=5) / RAP-002 (cardio, classif=5) / lire → true"
    (let [res (e2e-eval "DR-001" "RapportActivite" "RAP-002" "lire")]
      (is (true? (:result res)))))

  (testing "E2E-Rap-4 — INF-001 (cardio, cl=2) / RAP-002 (classif=5) / lire → false"
    (let [res (e2e-eval "INF-001" "RapportActivite" "RAP-002" "lire")]
      (is (false? (:result res)))))

  (testing "E2E-Rap-5 — SUS-001 (suspendu) / RAP-001 / lire → false (R-RAP-02 deny)"
    (let [res (e2e-eval "SUS-001" "RapportActivite" "RAP-001" "lire")]
      (is (false? (:result res))))))

;; =============================================================================
;; SECTION F — Tests de régression et cas limites
;; =============================================================================

(deftest filtrage-par-operation-test
  (testing "Les règles d'une opération ne filtrent pas l'autre opération"
    ;; DR-001 demande 'supprimer' — aucune règle DossierMedical n'est définie pour cette opération
    (with-redefs [prp/getGlobalPolicy (fn [_] policy-dossier-medical)
                  kpu/query-pip       kafka-query-mock]
      (let [res (e2e-eval "DR-001" "DossierMedical" "DM-001" "supprimer")]
        (is (false? (:result res)))))))     ; aucune règle applicable → deny

(deftest multi-conditions-toutes-doivent-matcher-test
  (testing "Toutes les conditions d'une règle doivent être vraies (ET implicite)"
    (let [rule (edn/read-string
                "{:name \"R\" :effect \"allow\"
                  :conditions [[= [Person $s role] \"medecin-chef\"]
                               [= [Person $s service] \"cardiologie\"]
                               [>= [Person $s clearance] \"4\"]]}")
          ok  {:subject {:role "medecin-chef" :service "cardiologie" :clearance "5"} :resource {}}
          ko1 {:subject {:role "infirmier"    :service "cardiologie" :clearance "5"} :resource {}}
          ko2 {:subject {:role "medecin-chef" :service "pediatrie"   :clearance "5"} :resource {}}
          ko3 {:subject {:role "medecin-chef" :service "cardiologie" :clearance "3"} :resource {}}]
      (is (true?  (:value (rule/evaluateRule rule ok))))
      (is (false? (:value (rule/evaluateRule rule ko1))))
      (is (false? (:value (rule/evaluateRule rule ko2))))
      (is (false? (:value (rule/evaluateRule rule ko3)))))))

(deftest valeurs-numeriques-coercees-en-string-test
  (testing "Les nombres venant des PIPs sont coercés en string pour les opérateurs"
    ;; attfun/> et >= appellent edn/read-string, donc "50000" → 50000
    (let [rule (edn/read-string
                "{:name \"R\" :effect \"allow\"
                  :conditions [[< [Facture $r montant] [Person $s seuil]]]}")
          req {:subject  {:seuil "50000"}
               :resource {:montant "30000"}}]
      (is (true? (:value (rule/evaluateRule rule req))))))
  (testing "Nombre entier converti en string via coerce-str"
    (let [n 42]
      (is (= "42" (#'autho.jsonrule/coerce-str n))))))
