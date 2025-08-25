(ns autho.pip-test
  (:require [clojure.test :refer :all]
            [autho.pip :as sut]))

(deftest csv-pip-test
  (testing "Test for the :csv PIP method"
    (let [pip-decl {:class :user
                    :attributes [:name :email :city]
                    :pip {:type :csv :path "resources/users.csv"}}
          user-obj {:class :user :id "user1"}]
      (is (= {:id "user1", :name "Alice", :email "alice@example.com", :city "Paris"}
             (sut/callPip pip-decl "name" user-obj))))
    (let [pip-decl {:class :user
                    :attributes [:name :email :city]
                    :pip {:type :csv :path "resources/users.csv"}}
          user-obj {:class :user :id "user2"}]
      (is (= {:id "user2", :name "Bob", :email "bob@example.com", :city "London"}
             (sut/callPip pip-decl "email" user-obj))))
    (let [pip-decl {:class :user
                    :attributes [:name :email :city]
                    :pip {:type :csv :path "resources/users.csv"}}
          user-obj {:class :user :id "user4"}]
      (is (nil? (sut/callPip pip-decl "name" user-obj))))))
