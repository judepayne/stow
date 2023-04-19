(ns stow.api
  (:require [stow.db :as db]
            [stow.auth :as auth :refer [authenticated?]]
            [clojure.string :as str]))


(declare logout)


(def cmds
  {:list #'db/list-keys
   :list-parents #'db/get-parents
   :add-node #'db/add-node
   :add-nodes #'db/add-nodes
   :add-multi #'db/add-multi
   :count-nodes #'db/count-nodes
   :count-parents #'db/count-parents
   :get-nodes-by-parent #'db/get-nodes-by-parent
   :get-parent  #'db/get-parent
   :change-parent-name #'db/change-parent-name
   :get-nodes-by-key #'db/get-nodes-by-key
   :get-node #'db/get-node
   :get-in-node #'db/get-in-node
   :get-all-nodes #'db/get-all-nodes
   :update-node-with #'db/update-node-with
   :update-node #'db/update-node
   :delete-node #'db/delete-node
   :delete-parent #'db/delete-parent
   :previous-versions #'db/previous-versions
   :restore #'db/restore
   :init #'db/init
   :log-out #'logout
   :authenticated #'authenticated?})

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
  (var-get (get cmds cmd)))



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
      {:result (apply f args)}
      (catch clojure.lang.ExceptionInfo e
        {:error (.getMessage e)})
      (catch Exception e
        {:error (.getMessage e)})
      (catch java.lang.AssertionError e
        {:error (.getMessage e)}))
    {:error "That command doesn't exist. Try the :help cmd for a list"}))
