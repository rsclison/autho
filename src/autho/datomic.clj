(ns autho.datomic
   (:require [datomic.api :as d])
  (:import (java.io File)
           (org.slf4j LoggerFactory)))

(defonce logger (LoggerFactory/getLogger "autho.datomic"))


(def uri "datomic:free://resources/datomdb")

;; Fonction pour initialiser la base de données si elle n'existe pas
(defn init-db []
  (when-not (d/create-database uri)
    (.info logger "La base de données existe déjà.")))

;; Fonction pour obtenir une connexion à la base de données
(defn get-connection []
  (d/connect uri))

;; Schéma de base pour un tuple (entité, attribut, valeur)
(def schema
  [{:db/ident :tuple/entity
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Une entité pour le tuple"
    :db.install/_attribute :db.part/db}
   {:db/ident :tuple/attribute
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc "Un attribut pour le tuple"
    :db.install/_attribute :db.part/db}
   {:db/ident :tuple/value
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "La valeur du tuple"
    :db.install/_attribute :db.part/db}])

;; Fonction pour initialiser le schéma de la base de données
(defn init-schema [conn]
  (d/transact conn schema))

;; Fonction pour écrire un tuple (entité, attribut, valeur)
(defn write-tuple [conn entity attribute value]
  (let [tuple {:db/id (d/tempid :db.part/user)
               :tuple/entity entity
               :tuple/attribute attribute
               :tuple/value value}]
    (d/transact conn [tuple])))

;; Fonction pour lire la dernière valeur d'un tuple par entité et attribut
(defn read-tuple [conn entity attribute]
  (let [db (d/db conn)
        result (d/q '[:find ?v
                      :in $ ?e ?a
                      :where
                      [?t :tuple/entity ?e]
                      [?t :tuple/attribute ?a]
                      [?t :tuple/value ?v]]
                    db entity attribute)]
    (if (seq result)
      (ffirst result)
      (str "Pas de valeur trouvée pour l'entité: " entity ", attribut: " attribute))))

;; Initialiser la base de données et le schéma
(defn setup []
  (init-db)
  (let [conn (get-connection)]
    (init-schema conn)
    conn))

;; Exemple d'utilisation
(defn -main []
  (let [conn (setup)]
    (write-tuple conn "entite1" :attribut1 "valeur1")
    (.info logger "Valeur pour ('entite1', :attribut1): {}" (read-tuple conn "entite1" :attribut1))))
