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
         :parents-by-child {}
         :children-by-parent {}
         :memberships-by-member {}
         :members-by-group {}}))

(defonce relation-rewrites
  (atom {}))

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

(defn- rewrite-row
  [relation rewritten-relation]
  {:relation (name relation)
   :rewritten_relation (name rewritten-relation)})

(defn- row->tuple
  [row]
  {:subject {:class (:subject_class row)
             :id (:subject_id row)}
   :relation (:relation row)
   :resource {:class (:resource_class row)
              :id (:resource_id row)}})

(defn- rows->rewrites
  [rows]
  (reduce (fn [rewrites {:keys [relation rewritten_relation]}]
            (update rewrites relation (fnil conj []) rewritten_relation))
          {}
          rows))

(defn- add-to-index
  [store tuple]
  (cond-> (update store :tuples conj tuple)
    (= "parent" (:relation tuple))
    (update-in [:parents-by-child (:subject tuple)]
               (fnil conj #{})
               (:resource tuple))
    (= "parent" (:relation tuple))
    (update-in [:children-by-parent (:resource tuple)]
               (fnil conj #{})
               (:subject tuple))
    (= "member" (:relation tuple))
    (update-in [:memberships-by-member (:subject tuple)]
               (fnil conj #{})
               (:resource tuple))
    (= "member" (:relation tuple))
    (update-in [:members-by-group (:resource tuple)]
               (fnil conj #{})
               (:subject tuple))))

(defn- remove-from-index
  [store tuple]
  (cond-> (update store :tuples disj tuple)
    (= "parent" (:relation tuple))
    (update-in [:parents-by-child (:subject tuple)]
               (fnil disj #{})
               (:resource tuple))
    (= "parent" (:relation tuple))
    (update-in [:children-by-parent (:resource tuple)]
               (fnil disj #{})
               (:subject tuple))
    (= "member" (:relation tuple))
    (update-in [:memberships-by-member (:subject tuple)]
               (fnil disj #{})
               (:resource tuple))
    (= "member" (:relation tuple))
    (update-in [:members-by-group (:resource tuple)]
               (fnil disj #{})
               (:subject tuple))))

(defn- build-index
  [tuples]
  (reduce add-to-index
          {:tuples #{}
           :parents-by-child {}
           :children-by-parent {}
           :memberships-by-member {}
           :members-by-group {}}
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
    (jdbc/execute! db
                   ["CREATE TABLE IF NOT EXISTS REBAC_RELATION_REWRITES (
         id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
         relation           VARCHAR(255) NOT NULL,
         rewritten_relation VARCHAR(255) NOT NULL,
         created_at         TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
         UNIQUE (relation, rewritten_relation)
       )"])
    (let [tuples (mapv row->tuple
                       (jdbc/query db
                                   ["SELECT subject_class, subject_id, relation,
                                            resource_class, resource_id
                                       FROM REBAC_RELATIONS"]))
          rewrites (rows->rewrites
                    (jdbc/query db
                                ["SELECT relation, rewritten_relation
                                    FROM REBAC_RELATION_REWRITES
                                ORDER BY relation ASC, rewritten_relation ASC"]))]
      (reset! relation-tuples (build-index tuples))
      (reset! relation-rewrites rewrites)
      (reset! persistence-enabled? true)
      (.info logger "REBAC_RELATIONS table ready with {} tuple(s) and {} rewrite(s)"
             (count tuples)
             (reduce + 0 (map count (vals rewrites)))))
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
                            :parents-by-child {}
                            :children-by-parent {}
                            :memberships-by-member {}
                            :members-by-group {}})
   (when (and persist @persistence-enabled?)
     (jdbc/delete! db :rebac_relations ["1 = 1"]))
   true))

(defn list-relations
  []
  (vec (:tuples @relation-tuples)))

(defn set-relation-rewrite!
  "Defines a userset rewrite for a derived relation.
   Example: (set-relation-rewrite! \"can-read\" [\"viewer\" \"editor\"])."
  [relation derived-relations]
  (let [relation (name relation)
        derived-relations (vec (distinct (map name derived-relations)))]
    (when @persistence-enabled?
      (jdbc/with-db-transaction [tx db]
        (jdbc/delete! tx :rebac_relation_rewrites ["relation = ?" relation])
        (doseq [derived-relation derived-relations]
          (jdbc/insert! tx :rebac_relation_rewrites (rewrite-row relation derived-relation)))))
    (swap! relation-rewrites assoc relation derived-relations)
    (get @relation-rewrites relation)))

(defn delete-relation-rewrite!
  [relation]
  (let [relation (name relation)]
    (when @persistence-enabled?
      (jdbc/delete! db :rebac_relation_rewrites ["relation = ?" relation]))
    (swap! relation-rewrites dissoc relation)
    true))

(defn clear-relation-rewrites!
  ([] (clear-relation-rewrites! {}))
  ([{:keys [persist]}]
   (reset! relation-rewrites {})
   (when (and persist @persistence-enabled?)
     (jdbc/delete! db :rebac_relation_rewrites ["1 = 1"]))
   true))

(defn list-relation-rewrites
  []
  @relation-rewrites)

(defn- direct-relation?
  [tuples subject relation resource]
  (contains? tuples (relation-tuple subject relation resource)))

(defn- parent-resources
  [store resource-key]
  (get-in store [:parents-by-child resource-key] #{}))

(defn- member-groups
  [store member-key]
  (get-in store [:memberships-by-member member-key] #{}))

(defn- child-resources
  [store parent-key]
  (get-in store [:children-by-parent parent-key] #{}))

(defn- group-members
  [store group-key]
  (get-in store [:members-by-group group-key] #{}))

(defn- entity-matches-class?
  [entity class-name]
  (or (nil? class-name)
      (= class-name (:class entity))))

(defn- sort-entities
  [entities]
  (->> entities
       distinct
       (sort-by (juxt :class :id))
       vec))

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

(defn- subject-paths
  [store subject-key max-depth]
  (loop [frontier [{:subject subject-key
                    :path [subject-key]}]
         visited #{}
         paths []
         depth 0]
    (cond
      (empty? frontier)
      paths

      (> depth max-depth)
      paths

      :else
      (let [{:keys [subject path] :as current} (first frontier)
            remaining (subvec (vec frontier) 1)]
        (if (contains? visited subject)
          (recur remaining visited paths depth)
          (let [groups (remove visited (member-groups store subject))
                group-paths (mapv (fn [group]
                                    {:subject group
                                     :path (conj path group)})
                                  groups)]
            (recur (vec (concat remaining group-paths))
                   (conj visited subject)
                   (conj paths current)
                   (inc depth))))))))

(defn- descendant-resource-paths
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
          (let [children (remove visited (child-resources store resource))
                child-paths (mapv (fn [child]
                                    {:resource child
                                     :path (conj path child)})
                                  children)]
            (recur (vec (concat remaining child-paths))
                   (conj visited resource)
                   (conj paths current)
                   (inc depth))))))))

(defn- descendant-subject-paths
  [store subject-key max-depth]
  (loop [frontier [{:subject subject-key
                    :path [subject-key]}]
         visited #{}
         paths []
         depth 0]
    (cond
      (empty? frontier)
      paths

      (> depth max-depth)
      paths

      :else
      (let [{:keys [subject path] :as current} (first frontier)
            remaining (subvec (vec frontier) 1)]
        (if (contains? visited subject)
          (recur remaining visited paths depth)
          (let [members (remove visited (group-members store subject))
                member-paths (mapv (fn [member]
                                     {:subject member
                                      :path (conj path member)})
                                   members)]
            (recur (vec (concat remaining member-paths))
                   (conj visited subject)
                   (conj paths current)
                   (inc depth))))))))

(defn- relation-paths
  [relation max-depth]
  (let [root (name relation)]
    (loop [frontier [{:relation root
                      :path [root]}]
           visited #{}
           paths []
           depth 0]
      (cond
        (empty? frontier)
        paths

        (> depth max-depth)
        paths

        :else
        (let [{:keys [relation path] :as current} (first frontier)
              remaining (subvec (vec frontier) 1)]
          (if (contains? visited relation)
            (recur remaining visited paths depth)
            (let [derived (remove visited (get @relation-rewrites relation []))
                  derived-paths (mapv (fn [derived-relation]
                                        {:relation derived-relation
                                         :path (conj path derived-relation)})
                                      derived)]
              (recur (vec (concat remaining derived-paths))
                     (conj visited relation)
                     (conj paths current)
                     (inc depth)))))))))

(defn list-accessible-resources
  "Lists resources for which subject has relation.
   Rewrites, group membership and resource inheritance are applied by default.
   Pass {:resource-class \"Document\"} to filter returned resources."
  ([subject relation]
   (list-accessible-resources subject relation {}))
  ([subject relation {:keys [resource-class inherited max-depth]
                      :or {inherited true
                           max-depth default-max-depth}}]
   (let [store @relation-tuples
         tuples (:tuples store)
         subject-key (entity-key subject)
         subject-candidates (set (map :subject
                                      (if inherited
                                        (subject-paths store subject-key max-depth)
                                        [{:subject subject-key
                                          :path [subject-key]}])))
         relation-candidates (set (map :relation (relation-paths relation max-depth)))
         matched-resources (for [{:keys [subject relation resource]} tuples
                                 :when (and (contains? subject-candidates subject)
                                            (contains? relation-candidates relation))]
                             resource)
         resources (if inherited
                     (mapcat #(map :resource
                                    (descendant-resource-paths store % max-depth))
                             matched-resources)
                     matched-resources)]
     (->> resources
          (filter #(entity-matches-class? % resource-class))
          sort-entities))))

(defn list-authorized-subjects
  "Lists subjects that have relation to resource.
   Rewrites, nested group membership and resource inheritance are applied by default.
   Pass {:subject-class \"Person\"} to filter returned subjects."
  ([resource relation]
   (list-authorized-subjects resource relation {}))
  ([resource relation {:keys [subject-class inherited max-depth]
                       :or {inherited true
                            max-depth default-max-depth}}]
   (let [store @relation-tuples
         tuples (:tuples store)
         resource-key (entity-key resource)
         resource-candidates (set (map :resource
                                       (if inherited
                                         (ancestor-paths store resource-key max-depth)
                                         [{:resource resource-key
                                           :path [resource-key]}])))
         relation-candidates (set (map :relation (relation-paths relation max-depth)))
         matched-subjects (for [{:keys [subject relation resource]} tuples
                                :when (and (contains? resource-candidates resource)
                                           (contains? relation-candidates relation))]
                            subject)
         subjects (if inherited
                    (mapcat #(map :subject
                                   (descendant-subject-paths store % max-depth))
                            matched-subjects)
                    matched-subjects)]
     (->> subjects
          (filter #(entity-matches-class? % subject-class))
          sort-entities))))

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
         subject-key (entity-key subject)
         resource-key (entity-key resource)
         subject-candidates (if inherited
                              (subject-paths store subject-key max-depth)
                              [{:subject subject-key
                                :path [subject-key]}])
         resource-candidates (if inherited
                               (ancestor-paths store resource-key max-depth)
                               [{:resource resource-key
                                 :path [resource-key]}])
         match (first
                (for [relation-candidate (relation-paths relation max-depth)
                      subject-candidate subject-candidates
                      resource-candidate resource-candidates
                      :when (direct-relation? tuples
                                              (:subject subject-candidate)
                                              (:relation relation-candidate)
                                              (:resource resource-candidate))]
                  {:relation-candidate relation-candidate
                   :subject-candidate subject-candidate
                   :resource-candidate resource-candidate}))]
     (cond-> {:allowed (boolean match)
              :subject subject-key
              :relation (name relation)
              :resource resource-key}
       match
       (assoc :matchedSubject (get-in match [:subject-candidate :subject])
              :matchedRelation (get-in match [:relation-candidate :relation])
              :matchedResource (get-in match [:resource-candidate :resource])
              :inherited (or (not= subject-key (get-in match [:subject-candidate :subject]))
                             (not= resource-key (get-in match [:resource-candidate :resource]))
                             (not= (name relation) (get-in match [:relation-candidate :relation])))
              :path (get-in match [:resource-candidate :path]))
       (and match (not= subject-key (get-in match [:subject-candidate :subject])))
       (assoc :subjectPath (get-in match [:subject-candidate :path]))
       (and match (not= (name relation) (get-in match [:relation-candidate :relation])))
       (assoc :relationPath (get-in match [:relation-candidate :path]))))))

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
