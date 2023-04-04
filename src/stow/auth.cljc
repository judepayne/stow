(ns stow.auth
  #?(:bb (:require [babashka.pods :as pods])
     :clj (:require [buddy.hashers :as pwd-hashers])))


#?(:bb (pods/load-pod 'org.babashka/buddy "0.3.4"))

#?(:bb (require '[pod.babashka.buddy.hashers :as pwd-hashers]))


;; Overall cryptographic scheme of stow.
;; -------------------------------------
;; At initialization, a salt is generated and kept. A user supplied passphrase
;; is kept emphemerally in memory in the passphrase atom. It is also hashed with
;; scrypt. The salt and scrypt hash are kept in the database.
;; At next authentication (login) the new user supplied passphrase is verified
;; against the scrypt hash. If it verifies, the user is authenticated and the
;; passphrase stored in memory again.

;; When a write or read operation are called (and the user is authenticated) the data
;; is aes256 encrypted/ decrypted using the stored salt and passphrase held in memory.
;; encryption/ decryption are in the cipher namespace.

;; Stow might have additional authetnication methods in future, hence the protocols.
(defprotocol P 
  (foo [this]) 
  (bar-me [this] [this y]))

(defprotocol Authenticate
  "A Protocol for initializing and authenticating
   against an authentication method.
   May capture password or config in some updated state."

  (init-prompt [this]
    "Returns a string prompting the user for information
     required for initialization.")
  
  (init! [this password-or-confifg]
    "Initializes the authentication method.")

  (verify-prompt [this]
    "Returns a string prompting the user for information
     requried for verification.")
  
  (verify [this & args]
    "Authenticates the user. Should return
     true or false."))


(defprotocol Authenticated
  "A protocol for verifying is we're in an
   authenticated state and for setting that state to false."
  
  (authed? [this] "Are we in an authenticated state?")
  
  (logout! [this] "Sets the state to not authenticated."))


;; -----State-----
;; holds the passphrase emphemerally in memory
(def passphrase (atom nil))



;; ------Password Authentication-------

(defn- scrypt-hash
  "Generates a strong hash of the password."
  [password]
  (-> password
      (pwd-hashers/derive {:alg :scrypt})))


(defn- scrypt-verify
  "Verifies password (plain text) against master-hash."
  [password master-hash]
  (:valid (pwd-hashers/verify password master-hash)))


;; public api, authentication.

(def ^:private pwd-prompt "Please enter passphrase.")


(defrecord Password-Authenticator [state]
  
  Authenticate

  (init-prompt [this] pwd-prompt)
  
  (init! [this password]
    "Stores the password (passphrase) in memory and returns an
     scrypt hash of the password."      
    (do (reset! (:state this) password)
        (scrypt-hash password)))

  (verify-prompt [this] pwd-prompt)
  
  (verify [this password master-hash]
    "Tries to authenticate by verifying the supplied password (passphrase) against
     the stored scrypt hash. If successful, returns the password, otherwise nil."
    (if (scrypt-verify password master-hash)
      (reset! (:state this) password)
      (reset! (:state this) nil)))

  Authenticated

  (authed? [this]
    "Returns true if authenticated, otherwise false."
    (if @(:state this) true false))

  (logout! [this]
    "Clears the passphrase atom, thus making `authed?` false"
    (reset! (:state this) nil)))


(def pwd-authenticator
  (Password-Authenticator. passphrase))


(defn authenticated?
  "Public function that returns true if authenticated."
  [] (authed? pwd-authenticator))


(defn logout
  "Public function to logout."
  [] (logout! pwd-authenticator))
