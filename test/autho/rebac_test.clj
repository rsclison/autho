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

(deftest inherited-resource-relation-test
  (let [subject {:class "Person" :id "alice"}
        document {:class "Document" :id "doc-1"}
        folder {:class "Folder" :id "folder-1"}
        workspace {:class "Workspace" :id "workspace-1"}]
    (rebac/add-relation! document "parent" folder)
    (rebac/add-relation! folder "parent" workspace)
    (rebac/add-relation! subject "viewer" workspace)
    (is (true? (rebac/has-relation? subject "viewer" workspace)))
    (is (true? (rebac/has-relation? subject "viewer" folder)))
    (is (true? (rebac/has-relation? subject "viewer" document)))
    (is (false? (rebac/has-relation? subject "editor" document)))
    (is (false? (rebac/has-relation? subject "viewer" document {:inherited false})))))

(deftest inherited-resource-relation-stops-on-cycles-test
  (let [subject {:class "Person" :id "alice"}
        folder-a {:class "Folder" :id "a"}
        folder-b {:class "Folder" :id "b"}]
    (rebac/add-relation! folder-a "parent" folder-b)
    (rebac/add-relation! folder-b "parent" folder-a)
    (rebac/add-relation! subject "viewer" folder-b)
    (is (true? (rebac/has-relation? subject "viewer" folder-a)))))
