(ns stow.serialize
  (:require [clojure.edn :refer [read-string]])
  (:refer-clojure :exclude [read-string]))


;; The data that we store in stash under the value key is a clojure map
;; e.g.:    {:uid "jude@test.com" :pwd "ghfgf%FFggsh12+3" :note "blah blah" ... }
;; to store in stash, we'll need strings and use edn for that.
;; this ns is for writing and reading those strings.


(defn- string-reader [s] s)


(def ^:private readers
  {'stow.data/string string-reader})


(def ^:private tags
  {string? 'stow.data/string})


;; From clojure.walk
(defn- walk
  "same as clojure.walk/walk.
   tweaked for map entries to allow tagged literals."
  [inner outer form]
  (cond
    (list? form) (outer (apply list (map inner form)))
    (instance? clojure.lang.IMapEntry form)
    (outer [(inner (key form)) (inner (val form))]) ;; tweak
    (seq? form) (outer (doall (map inner form)))
    (instance? clojure.lang.IRecord form)
    (outer (reduce (fn [r x] (conj r (inner x))) form form))
    (coll? form) (outer (into (empty form) (map inner form)))
    :else (outer form)))


(defn- postwalk
  "same as clojure.walk/postwalk"
  [f form]
  (walk (partial postwalk f) f form))


(defn- tag-form
  [form]
  (reduce
   (fn [form [pred tag]]
     (if (pred form)
       (tagged-literal tag form)
       form))
   form
   tags))


(def ^:private tag (partial postwalk tag-form))


(defn prn-data
  "Serializes a clojure form into an edn string.
  tags (sub) forms as per the `tags` map."
  [form]
  (pr-str (tag form)))


(defn read-data
  "Deserializes edn data, included tagged literal forms."
  [s]
  (read-string {:readers readers} s))
