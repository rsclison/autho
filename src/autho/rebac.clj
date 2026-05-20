(ns autho.rebac)

(defonce relation-tuples
  (atom #{}))

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
    (swap! relation-tuples conj tuple)
    tuple))

(defn remove-relation!
  "Removes a subject-relation-resource tuple from the in-memory relation graph."
  [subject relation resource]
  (let [tuple (relation-tuple subject relation resource)]
    (swap! relation-tuples disj tuple)
    tuple))

(defn clear-relations!
  []
  (reset! relation-tuples #{})
  true)

(defn list-relations
  []
  (vec @relation-tuples))

(defn has-relation?
  "Returns true when subject has relation to resource.
   This first version checks direct tuples only; graph traversal can be added
   later without changing the policy predicate shape."
  [subject relation resource]
  (contains? @relation-tuples (relation-tuple subject relation resource)))
