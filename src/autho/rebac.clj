(ns autho.rebac
  (:require [clojure.java.jdbc :as jdbc])
  (:import (org.slf4j LoggerFactory)))

(defonce ^:private logger (LoggerFactory/getLogger "autho.rebac"))

(def default-max-depth 8)

(def ^:private h2-policy-cipher-key (System/getenv "H2_POLICY_CIPHER_KEY"))
(def ^:private h2-policy-db-path
  (or (System/getenv "AUTHO_POLICY_DB_PATH")
      (System/getProperty "autho.policy.db.path")
      "./resources/h2db"))

(def ^:private db
  (merge
   {:classname "org.h2.Driver"
    :subprotocol "h2"
    :user "sa"}
   (if h2-policy-cipher-key
     {:subname (str h2-policy-db-path ";CIPHER=AES")
      :password (str h2-policy-cipher-key " ")}
     {:subname h2-policy-db-path
      :password ""})))

(defonce relation-tuples
  (atom {:tuples #{}
         :parents-by-child {}}))

(defonce persistence-enabled?
  (atom false))

(defn- entity-key
  [entity]
  {:class (or (:class entity) (:resourceClass entity))
   :id (:id entity)})

(defn- relation-tuple
  [subject relation resource]
  {:subject (entity-key subject)
   :relation (name relation)
   :resource (entity-key resource)})

(defn- tuple->row
  [{:keys [subject relation resource]}]
  {:subject_class (:class subject)
   :subject_id (:id subject)
   :relation relation
   :resource_class (:class resource)
   :resource_id (:id resource)})

(defn- row->tuple
  [row]
  {:subject {:class (:subject_class row)
             :id (:subject_id row)}
   :relation (:relation row)
   :resource {:class (:resource_class row)
              :id (:resource_id row)}})

(defn- add-to-index
  [store tuple]
  (cond-> (update store :tuples conj tuple)
    (= "parent" (:relation tuple))
    (update-in [:parents-by-child (:subject tuple)]
               (fnil conj #{})
               (:resource tuple))))

(defn- remove-from-index
  [store tuple]
  (cond-> (update store :tuples disj tuple)
    (= "parent" (:relation tuple))
    (update-in [:parents-by-child (:subject tuple)]
               (fnil disj #{})
               (:resource tuple))))

(defn- build-index
  [tuples]
  (reduce add-to-index
          {:tuples #{}
           :parents-by-child {}}
          tuples))

(defn init!
  "Create the durable ReBAC tuple table and load the in-memory indexes."
  []
  (try
    (jdbc/execute! db
                   ["CREATE TABLE IF NOT EXISTS REBAC_RELATIONS (
         id             BIGINT AUTO_INCREMENT PRIMARY KEY,
         subject_class  VARCHAR(255) NOT NULL,
         subject_id     VARCHAR(255) NOT NULL,
         relation       VARCHAR(255) NOT NULL,
         resource_class VARCHAR(255) NOT NULL,
         resource_id    VARCHAR(255) NOT NULL,
         created_at     TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
         UNIQUE (subject_class, subject_id, relation, resource_class, resource_id)
       )"])
    (let [tuples (mapv row->tuple
                       (jdbc/query db
                                   ["SELECT subject_class, subject_id, relation,
                                            resource_class, resource_id
                                       FROM REBAC_RELATIONS"]))]
      (reset! relation-tuples (build-index tuples))
      (reset! persistence-enabled? true)
      (.info logger "REBAC_RELATIONS table ready with {} tuple(s)" (count tuples)))
    (catch Exception e
      (.error logger "Failed to initialize REBAC_RELATIONS: {}" (.getMessage e) e))))

(defn add-relation!
  "Adds a subject-relation-resource tuple to the in-memory relation graph."
  [subject relation resource]
  (let [tuple (relation-tuple subject relation resource)]
    (when @persistence-enabled?
      (try
        (jdbc/insert! db :rebac_relations (tuple->row tuple))
        (catch org.h2.jdbc.JdbcSQLIntegrityConstraintViolationException _ nil)))
    (swap! relation-tuples add-to-index tuple)
    tuple))

(defn remove-relation!
  "Removes a subject-relation-resource tuple from the in-memory relation graph."
  [subject relation resource]
  (let [tuple (relation-tuple subject relation resource)]
    (when @persistence-enabled?
      (jdbc/delete! db :rebac_relations
                    ["subject_class = ? AND subject_id = ? AND relation = ? AND resource_class = ? AND resource_id = ?"
                     (get-in tuple [:subject :class])
                     (get-in tuple [:subject :id])
                     (:relation tuple)
                     (get-in tuple [:resource :class])
                     (get-in tuple [:resource :id])]))
    (swap! relation-tuples remove-from-index tuple)
    tuple))

(defn clear-relations!
  "Clears in-memory relation indexes. Pass {:persist true} to also delete
   durable tuples after init! has enabled persistence."
  ([] (clear-relations! {}))
  ([{:keys [persist]}]
   (reset! relation-tuples {:tuples #{}
                            :parents-by-child {}})
   (when (and persist @persistence-enabled?)
     (jdbc/delete! db :rebac_relations ["1 = 1"]))
   true))

(defn list-relations
  []
  (vec (:tuples @relation-tuples)))

(defn- direct-relation?
  [tuples subject relation resource]
  (contains? tuples (relation-tuple subject relation resource)))

(defn- parent-resources
  [store resource-key]
  (get-in store [:parents-by-child resource-key] #{}))

(defn- ancestor-paths
  [store resource-key max-depth]
  (loop [frontier [{:resource resource-key
                    :path [resource-key]}]
         visited #{}
         paths []
         depth 0]
    (cond
      (empty? frontier)
      paths

      (> depth max-depth)
      paths

      :else
      (let [{:keys [resource path] :as current} (first frontier)
            remaining (subvec (vec frontier) 1)]
        (if (contains? visited resource)
          (recur remaining visited paths depth)
          (let [parents (remove visited (parent-resources store resource))
                parent-paths (mapv (fn [parent]
                                     {:resource parent
                                      :path (conj path parent)})
                                   parents)]
            (recur (vec (concat remaining parent-paths))
                   (conj visited resource)
                   (conj paths current)
                   (inc depth))))))))

(defn explain-relation
  "Explains whether subject has relation to resource.
   The explanation includes the matched resource and the parent path when a
   relation is inherited from an ancestor."
  ([subject relation resource]
   (explain-relation subject relation resource {}))
  ([subject relation resource {:keys [inherited max-depth]
                               :or {inherited true
                                    max-depth default-max-depth}}]
   (let [store @relation-tuples
         tuples (:tuples store)
         resource-key (entity-key resource)
         candidates (if inherited
                      (ancestor-paths store resource-key max-depth)
                      [{:resource resource-key
                        :path [resource-key]}])
         match (first (filter #(direct-relation? tuples subject relation (:resource %))
                              candidates))]
     (cond-> {:allowed (boolean match)
              :subject (entity-key subject)
              :relation (name relation)
              :resource resource-key}
       match
       (assoc :matchedResource (:resource match)
              :inherited (not= resource-key (:resource match))
              :path (:path match))))))

(defn has-relation?
  "Returns true when subject has relation to resource.
   Direct tuples are always checked. By default, the check also walks resource
   ancestry through `parent` tuples, so a relation granted on a parent resource
   applies to its descendants."
  ([subject relation resource]
   (has-relation? subject relation resource {}))
  ([subject relation resource {:keys [inherited max-depth]
                               :or {inherited true
                                    max-depth default-max-depth}}]
   (:allowed (explain-relation subject relation resource {:inherited inherited
                                                          :max-depth max-depth}))))
