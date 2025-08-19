(ns autho.utils-test
  (:require [clojure.test :refer :all]
            [autho.utils :as utils]
            [clojure.java.io :as io]))

(deftest load-edn-test
  (testing "loading a non-existent file"
    (is (thrown? java.io.IOException (utils/load-edn "non-existent-file.edn"))))

  (testing "loading an invalid EDN file"
    (let [invalid-edn-file "invalid.edn"]
      (spit invalid-edn-file "{:a 1, :b")
      (is (thrown? RuntimeException (utils/load-edn invalid-edn-file)))
      (io/delete-file invalid-edn-file))))
