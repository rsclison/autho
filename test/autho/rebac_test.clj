(ns autho.rebac-test
  (:require [clojure.test :refer :all]
            [autho.rebac :as rebac]
            [clojure.java.jdbc :as jdbc]))

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

(deftest explain-inherited-relation-test
  (let [subject {:class "Person" :id "alice"}
        document {:class "Document" :id "doc-1"}
        folder {:class "Folder" :id "folder-1"}]
    (rebac/add-relation! document "parent" folder)
    (rebac/add-relation! subject "viewer" folder)
    (is (= {:allowed true
            :subject {:class "Person" :id "alice"}
            :relation "viewer"
            :resource {:class "Document" :id "doc-1"}
            :matchedSubject {:class "Person" :id "alice"}
            :matchedResource {:class "Folder" :id "folder-1"}
            :inherited true
            :path [{:class "Document" :id "doc-1"}
                   {:class "Folder" :id "folder-1"}]}
           (rebac/explain-relation subject "viewer" document)))))

(deftest explain-missing-relation-test
  (let [subject {:class "Person" :id "alice"}
        document {:class "Document" :id "doc-1"}]
    (is (= {:allowed false
            :subject {:class "Person" :id "alice"}
            :relation "viewer"
            :resource {:class "Document" :id "doc-1"}}
           (rebac/explain-relation subject "viewer" document)))))

(deftest group-membership-relation-test
  (let [subject {:class "Person" :id "alice"}
        team {:class "Group" :id "team-a"}
        resource {:class "Document" :id "doc-1"}]
    (rebac/add-relation! subject "member" team)
    (rebac/add-relation! team "viewer" resource)
    (is (true? (rebac/has-relation? subject "viewer" resource)))
    (is (false? (rebac/has-relation? subject "viewer" resource {:inherited false})))
    (is (= {:allowed true
            :subject {:class "Person" :id "alice"}
            :relation "viewer"
            :resource {:class "Document" :id "doc-1"}
            :matchedSubject {:class "Group" :id "team-a"}
            :matchedResource {:class "Document" :id "doc-1"}
            :inherited true
            :path [{:class "Document" :id "doc-1"}]
            :subjectPath [{:class "Person" :id "alice"}
                          {:class "Group" :id "team-a"}]}
           (rebac/explain-relation subject "viewer" resource)))))

(deftest nested-group-membership-relation-test
  (let [subject {:class "Person" :id "alice"}
        team {:class "Group" :id "team-a"}
        org-admins {:class "Group" :id "org-admins"}
        resource {:class "Document" :id "doc-1"}]
    (rebac/add-relation! subject "member" team)
    (rebac/add-relation! team "member" org-admins)
    (rebac/add-relation! org-admins "viewer" resource)
    (is (true? (rebac/has-relation? subject "viewer" resource)))
    (is (= [{:class "Person" :id "alice"}
            {:class "Group" :id "team-a"}
            {:class "Group" :id "org-admins"}]
           (:subjectPath (rebac/explain-relation subject "viewer" resource))))))

(deftest durable-relations-survive-reinit-test
  (let [test-db {:classname "org.h2.Driver"
                 :subprotocol "h2:mem"
                 :subname "rebac-test;DB_CLOSE_DELAY=-1"
                 :user "sa"
                 :password ""}
        subject {:class "Person" :id "alice"}
        resource {:class "Document" :id "doc-1"}]
    (with-redefs-fn {#'rebac/db test-db
                     #'rebac/persistence-enabled? (atom false)}
      (fn []
        (try
          (jdbc/execute! test-db ["DROP TABLE IF EXISTS REBAC_RELATIONS"])
          (rebac/init!)
          (rebac/clear-relations! {:persist true})
          (rebac/add-relation! subject "viewer" resource)
          (rebac/clear-relations!)
          (is (false? (rebac/has-relation? subject "viewer" resource)))
          (rebac/init!)
          (is (true? (rebac/has-relation? subject "viewer" resource)))
          (finally
            (rebac/clear-relations! {:persist true})))))))

(deftest inherited-resource-relation-stops-on-cycles-test
  (let [subject {:class "Person" :id "alice"}
        folder-a {:class "Folder" :id "a"}
        folder-b {:class "Folder" :id "b"}]
    (rebac/add-relation! folder-a "parent" folder-b)
    (rebac/add-relation! folder-b "parent" folder-a)
    (rebac/add-relation! subject "viewer" folder-b)
    (is (true? (rebac/has-relation? subject "viewer" folder-a)))))
