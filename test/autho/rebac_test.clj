(ns autho.rebac-test
  (:require [clojure.test :refer :all]
            [autho.rebac :as rebac]))

(use-fixtures :each
  (fn [f]
    (rebac/clear-relations!)
    (f)
    (rebac/clear-relations!)))

(deftest direct-relation-tuples-test
  (let [subject {:class "Person" :id "alice"}
        resource {:class "Document" :id "doc-1"}]
    (is (false? (rebac/has-relation? subject "viewer" resource)))
    (is (= {:subject {:class "Person" :id "alice"}
            :relation "viewer"
            :resource {:class "Document" :id "doc-1"}}
           (rebac/add-relation! subject "viewer" resource)))
    (is (true? (rebac/has-relation? subject "viewer" resource)))
    (is (false? (rebac/has-relation? subject "editor" resource)))
    (rebac/remove-relation! subject "viewer" resource)
    (is (false? (rebac/has-relation? subject "viewer" resource)))))
