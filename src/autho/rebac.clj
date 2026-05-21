(ns autho.rebac)

(def default-max-depth 8)

(defonce relation-tuples
  (atom {:tuples #{}
         :parents-by-child {}}))

(defn- entity-key
  [entity]
  {:class (or (:class entity) (:resourceClass entity))
   :id (:id entity)})

(defn- relation-tuple
  [subject relation resource]
  {:subject (entity-key subject)
   :relation (name relation)
   :resource (entity-key resource)})

(defn add-relation!
  "Adds a subject-relation-resource tuple to the in-memory relation graph."
  [subject relation resource]
  (let [tuple (relation-tuple subject relation resource)]
    (swap! relation-tuples
           (fn [store]
             (cond-> (update store :tuples conj tuple)
               (= "parent" (:relation tuple))
               (update-in [:parents-by-child (:subject tuple)]
                          (fnil conj #{})
                          (:resource tuple)))))
    tuple))

(defn remove-relation!
  "Removes a subject-relation-resource tuple from the in-memory relation graph."
  [subject relation resource]
  (let [tuple (relation-tuple subject relation resource)]
    (swap! relation-tuples
           (fn [store]
             (cond-> (update store :tuples disj tuple)
               (= "parent" (:relation tuple))
               (update-in [:parents-by-child (:subject tuple)]
                          (fnil disj #{})
                          (:resource tuple)))))
    tuple))

(defn clear-relations!
  []
  (reset! relation-tuples {:tuples #{}
                           :parents-by-child {}})
  true)

(defn list-relations
  []
  (vec (:tuples @relation-tuples)))

(defn- direct-relation?
  [tuples subject relation resource]
  (contains? tuples (relation-tuple subject relation resource)))

(defn- parent-resources
  [store resource-key]
  (get-in store [:parents-by-child resource-key] #{}))

(defn- ancestor-keys
  [store resource-key max-depth]
  (loop [frontier [resource-key]
         visited #{}
         ancestors []
         depth 0]
    (cond
      (empty? frontier)
      ancestors

      (> depth max-depth)
      ancestors

      :else
      (let [current (first frontier)
            remaining (subvec (vec frontier) 1)]
        (if (contains? visited current)
          (recur remaining visited ancestors depth)
          (let [parents (remove visited (parent-resources store current))]
            (recur (vec (concat remaining parents))
                   (conj visited current)
                   (conj ancestors current)
                   (inc depth))))))))

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
   (let [store @relation-tuples
         tuples (:tuples store)
         resource-key (entity-key resource)
         candidate-resources (if inherited
                               (ancestor-keys store resource-key max-depth)
                               [resource-key])]
     (boolean
      (some #(direct-relation? tuples subject relation %) candidate-resources)))))
