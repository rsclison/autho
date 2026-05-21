(ns autho.rebac-test
  (:require [clojure.test :refer :all]
            [autho.rebac :as rebac]
            [clojure.java.jdbc :as jdbc]))

(use-fixtures :each
  (fn [f]
    (rebac/clear-relations!)
    (rebac/clear-relation-rewrites!)
    (f)
    (rebac/clear-relations!)
    (rebac/clear-relation-rewrites!)))

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
            :matchedRelation "viewer"
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
            :matchedRelation "viewer"
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

(deftest relation-rewrite-test
  (let [subject {:class "Person" :id "alice"}
        resource {:class "Document" :id "doc-1"}]
    (rebac/set-relation-rewrite! "can-read" ["viewer" "editor"])
    (rebac/add-relation! subject "viewer" resource)
    (is (true? (rebac/has-relation? subject "can-read" resource)))
    (is (= {:allowed true
            :subject {:class "Person" :id "alice"}
            :relation "can-read"
            :resource {:class "Document" :id "doc-1"}
            :matchedSubject {:class "Person" :id "alice"}
            :matchedRelation "viewer"
            :matchedResource {:class "Document" :id "doc-1"}
            :inherited true
            :path [{:class "Document" :id "doc-1"}]
            :relationPath ["can-read" "viewer"]}
           (rebac/explain-relation subject "can-read" resource)))))

(deftest relation-rewrite-stops-on-cycles-test
  (let [subject {:class "Person" :id "alice"}
        resource {:class "Document" :id "doc-1"}]
    (rebac/set-relation-rewrite! "can-read" ["readable"])
    (rebac/set-relation-rewrite! "readable" ["can-read" "viewer"])
    (rebac/add-relation! subject "viewer" resource)
    (is (true? (rebac/has-relation? subject "can-read" resource)))
    (is (= ["can-read" "readable" "viewer"]
           (:relationPath (rebac/explain-relation subject "can-read" resource))))))

(deftest list-accessible-resources-includes-rewrites-groups-and-descendants-test
  (let [alice {:class "Person" :id "alice"}
        team {:class "Group" :id "team-a"}
        folder {:class "Folder" :id "folder-1"}
        doc-1 {:class "Document" :id "doc-1"}
        doc-2 {:class "Document" :id "doc-2"}]
    (rebac/set-relation-rewrite! "can-read" ["viewer"])
    (rebac/add-relation! alice "member" team)
    (rebac/add-relation! team "viewer" folder)
    (rebac/add-relation! doc-1 "parent" folder)
    (rebac/add-relation! doc-2 "parent" folder)
    (is (= [doc-1 doc-2]
           (rebac/list-accessible-resources alice
                                            "can-read"
                                            {:resource-class "Document"})))
    (is (empty? (rebac/list-accessible-resources alice
                                                 "can-read"
                                                 {:resource-class "Document"
                                                  :inherited false})))))

(deftest list-authorized-subjects-includes-nested-group-members-test
  (let [alice {:class "Person" :id "alice"}
        bob {:class "Person" :id "bob"}
        team {:class "Group" :id "team-a"}
        org-admins {:class "Group" :id "org-admins"}
        folder {:class "Folder" :id "folder-1"}
        doc {:class "Document" :id "doc-1"}]
    (rebac/set-relation-rewrite! "can-read" ["viewer"])
    (rebac/add-relation! alice "member" team)
    (rebac/add-relation! team "member" org-admins)
    (rebac/add-relation! bob "member" org-admins)
    (rebac/add-relation! org-admins "viewer" folder)
    (rebac/add-relation! doc "parent" folder)
    (is (= [alice bob]
           (rebac/list-authorized-subjects doc
                                           "can-read"
                                           {:subject-class "Person"})))))

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

(deftest durable-rewrites-survive-reinit-test
  (let [test-db {:classname "org.h2.Driver"
                 :subprotocol "h2:mem"
                 :subname "rebac-rewrite-test;DB_CLOSE_DELAY=-1"
                 :user "sa"
                 :password ""}]
    (with-redefs-fn {#'rebac/db test-db
                     #'rebac/persistence-enabled? (atom false)}
      (fn []
        (try
          (jdbc/execute! test-db ["DROP TABLE IF EXISTS REBAC_RELATION_REWRITES"])
          (jdbc/execute! test-db ["DROP TABLE IF EXISTS REBAC_RELATIONS"])
          (rebac/init!)
          (rebac/clear-relation-rewrites! {:persist true})
          (rebac/set-relation-rewrite! "can-read" ["viewer" "editor"])
          (rebac/clear-relation-rewrites!)
          (is (empty? (rebac/list-relation-rewrites)))
          (rebac/init!)
          (is (= {"can-read" ["editor" "viewer"]}
                 (rebac/list-relation-rewrites)))
          (finally
            (rebac/clear-relation-rewrites! {:persist true})))))))

(deftest inherited-resource-relation-stops-on-cycles-test
  (let [subject {:class "Person" :id "alice"}
        folder-a {:class "Folder" :id "a"}
        folder-b {:class "Folder" :id "b"}]
    (rebac/add-relation! folder-a "parent" folder-b)
    (rebac/add-relation! folder-b "parent" folder-a)
    (rebac/add-relation! subject "viewer" folder-b)
    (is (true? (rebac/has-relation? subject "viewer" folder-a)))))
