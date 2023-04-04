(ns stow.cipher
  (:require [clojure.string :as str]
            #?(:bb [pod.babashka.buddy.core.nonce :as nonce]
               :clj [buddy.core.nonce :as nonce])
            #?(:bb [pod.babashka.buddy.core.hash :as hash]
               :clj [buddy.core.hash :as hash])
            #?(:bb [pod.babashka.buddy.core.codecs :as codecs]
               :clj [buddy.core.codecs :as codecs])
            #?(:bb [pod.babashka.buddy.core.crypto :as c]
               :clj [buddy.core.crypto :as c])
            [stow.auth :as auth :refer [authenticated?]]))


;; ------Encryption and decryption-------
;; public api, encryption & decryption
(defn salt
  "Generates a 16 byte salt."
  []
  (-> (nonce/random-bytes 16) (codecs/bytes->hex)))


(defn encrypt
  "If in an authenticated state, encrypts data with aes256 using the passphrase
   held in memory and stored salt, otherwise throws an error."
  [data key salt]
  (if (authenticated?)
    (-> (#?(:bb c/block-cipher-encrypt
            :clj c/encrypt)
         (codecs/str->bytes data)
         (hash/sha512 key)
         (codecs/hex->bytes salt)
         {:alg :aes256-cbc-hmac-sha512})
        codecs/bytes->hex)
    (throw (ex-info "Cannot encrypt when unauthorized." {}))))


(defn decrypt
  "If in an authenticated state, decrypts data with aes256 using the passphrase
   held in memory and stored salt, otherwise throws an error."
  [encrypted key salt]
  (if (authenticated?)
    (-> (#?(:bb c/block-cipher-decrypt
            :clj c/decrypt)
         (codecs/hex->bytes encrypted)
         (hash/sha512 key)
         (codecs/hex->bytes salt)
         {:alg :aes256-cbc-hmac-sha512})
        codecs/bytes->str)
    (throw (ex-info "Cannot decrypt when unauthorized." {}))))
