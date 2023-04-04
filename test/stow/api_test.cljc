(ns stow.api-test
  (:require [stow.api :as api]
            [stow.db :as db]
            #?(:bb [babashka.fs :as fs])
            [clojure.test :refer [deftest testing is]]))


(defonce test-path "resources/stow.db")


(defonce test-pwd "pwd")


(defn- delete-if-exists [path]
  #?(:bb (fs/delete-if-exists path)
     :clj (clojure.java.io/delete-file path true)))


(defn- file-exists? [path]
  #?(:bb (fs/exists? path)
     :clj (.exists (clojure.java.io/file path))))


(delete-if-exists test-path)


(deftest init-db
  (testing "I can initiate the stow db"
    (let [result (api/do-cmd :init test-path test-pwd)]
      (is (true? result)))))


(deftest auth-db
  (testing "I can authenticate to the stow db"
    (let [result (api/do-cmd :init test-path test-pwd)]
      (is (true? result)))))


(deftest insert-node
  (let [node {:parent :private
              :key :my-key
              :value {:a {:b "private" :c [1 2 3]}}}]
    (testing "I can insert a node into the db"
      (let [result (api/do-cmd :add-node node)]
        (is (integer? result))))
    (testing "I can get it back again"
      (let [result (api/do-cmd :get-node (:parent node) (:key node))]
        (is (= node (select-keys result [:parent :key :value])))))))
