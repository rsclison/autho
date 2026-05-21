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
