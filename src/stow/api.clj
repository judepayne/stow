(ns stow.api
  (:require [stow.db :as db]
            [stow.auth :as auth :refer [authenticated?]]
            [clojure.string :as str]))


(declare logout)


(def cmds
  {:list {:cmd #'db/list-keys :requires-auth? true}
   :list-parents {:cmd #'db/get-parents :requires-auth? true}
   :add-node {:cmd #'db/add-node :requires-auth? true}
   :add-nodes {:cmd #'db/add-nodes :requires-auth? true}
   :get-nodes-by-parent {:cmd #'db/get-nodes-by-parent :requires-auth? true}
   :get-nodes-by-key {:cmd #'db/get-nodes-by-key :requires-auth? true}
   :get-node {:cmd #'db/get-node :requires-auth? true}
   :get-all-nodes {:cmd #'db/get-all-nodes :requires-auth? true}
   :update-node-with {:cmd #'db/update-node-with :requires-auth? true}
   :update-node {:cmd #'db/update-node :requires-auth? true}
   :delete-node {:cmd #'db/delete-node :requires-auth? true}
   :previous-versions {:cmd #'db/previous-versions :requires-auth? true}
   :restore {:cmd #'db/restore :requires-auth? true}
   :init {:cmd #'db/init :requires-auth? false}
   :log-out {:cmd #'logout :requires-auth? false}
   :authenticated {:cmd #'authenticated? :requires-auth? false}})

(defn- logout
  "Set all state to nil so that the user must re-init."
  []
  (do
    (auth/logout)
    (db/logout)
    true))


(defn- cmd-fn
  "Returns the function for the cmd."
  [cmd]
  (var-get (:cmd (get cmds cmd))))


(defn- requires-auth?
  "Returns true if the cmd requires to be in an authenticated state."
  [cmd]
  (:requires-auth? (get cmds cmd)))


;; public functions

(def available-cmds
  (into #{} (keys cmds)))


(defn do-cmd
  "Executes a stow command, where cmd is a keyword and args
   are arguments that should be passed to the command.
   Returns either {:result <result>} or {:error <error-msg>}."
  [cmd & args]
  (if-let [f (cmd-fn cmd)]
    (try
      (if-let [res (apply f args)]
        {:result res} {:error "Unsuccessful"})
      (catch clojure.lang.ExceptionInfo e
        {:error (.getMessage e)})
      (catch Exception e
        {:error (.getMessage e)})
      (catch java.lang.AssertionError e
        {:error (.getMessage e)}))
    {:error "That command doesn't exist. Try the :help cmd for a list"}))
