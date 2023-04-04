# Stow

[![bb compatible](https://raw.githubusercontent.com/babashka/babashka/master/logo/badge.svg)](https://babashka.org)

An encrypted store library for babashka/ clojure.

Stow stores your data securely in a local sqlite database using AES256 for encryption and Scrypt for password hashing. Use it in your app for storing secrets, for example [pass](https://github.com/judepayne/pass) a personal command line password manager.

## Release Information

[deps.edn/bb.edn](https://clojure.org/reference/deps_and_cli) dependency information:

As a git dep:

```clojure
io.github.judepayne/dictim {:git/tag "0.0.1" :git/sha "b64ebf3"}
``` 

## Usage


Stow has a `stow.api` namespace that exposes all the public functions.

````clojure
    user> (require '[stow.api :refer [do-cmd]])
    nil
    user> (do-cmd :init "stow.db" "mypassw0rd")
    {:result true}
````

`:init` is to initialize a new encrypted db file or authenticate against an existing one.

````clojure
    user> (do-cmd :add-node "facebook.com" "myFacebookPwd")
    {:result {:id 1, :parent :root}}
````

A stow node is identified by it's `:parent` and it's `:key`. When you don't specify a `:parent` when adding a node, it's defaulted to `:root`.

Having both `:parent` and `:key` gives flexibility on how to arrange your data. For example, you might choose to have a `:parent` as "facebook.com" (or :facebook - stow accepts most Clojure data structures), with multiple keys beneath it, e.g. `:uer`, `:password` etc, or you might choose to ignore `:parent` (accept the default `:root` and store multiple values in a clojure data structure stored under `:value`. See below.

````clojure
    user> (do-cmd :list)
    {:result ([:root "facebook.com"])}
````

A list of `:parent` `:key` tuples.

````clojure
    user> (do-cmd :get-all-nodes)
    {:result
     [{:id 1,
       :parent "a24ea0fa8a31881813a6717e620e4ce35a2de8f82e9561540380e648b5f554abf7e88858f23600959c195d8777e69e6d",
       :key      "a3d898f2cea4554a869473d36542c6c66e31c8ea26316f2f3a903dfd273b4ce4e39b228559a38b2bbb4d0dc4994b5dc3a944959cf128e4a8fcb0030e62346d9758a2eaeb50712fe6b82aa31fff01c2fb",
       :value      "a3d898f2cea4554a869473d36542c6c61a744a1c9c8b858fa46f0b4197fd002481af70a6ecdad565bca2e8c269d307155d34feba0cc648b386bebdfaff5654b962dc35365fd0fc838b94a4798a35025b",
       :version 1,
       :created "2023-04-04 20:27:41",
       :modified "2023-04-04 20:27:41"}]}
````

`:parent`, `:key` and `:value` are kept encrypted in the database.

````clojure
    user> (do-cmd :get-all-nodes :decrypt? true)
    {:result
     ({:key "facebook.com",
       :value "myFacebookPwd",
       :version 1,
       :created "2023-04-04 20:27:41",
       :modified "2023-04-04 20:27:41",
       :id 1,
       :parent :root})}
    user> (do-cmd :authenticated)
    {:result true}
````

The majority of functions require to be in an authenticated state.

````clojure
    user> (do-cmd :delete-node :root "facebook.com")
    {:result {:rows-affected 1, :last-inserted-id 0}}

    user> (do-cmd :add-node "facebook.com" {:user "myuserID" :pwd "myFacebookPwd"})
    {:result {:id 1, :parent :root}}
````

Clojure data structures can be used in the `:value` key. They can be used as `:parents` and `:keys` for that matter but the use cases are fewer!

````clojure
    user> (do-cmd :update-node-with :root "facebook.com" assoc :notes "close this account")
    {:result {:rows-affected 1, :last-inserted-id 0}}
````

`:update-node-with` allows you to apply functions to update clojure data structure's stored in `:value` keys. Here we're assoc'ing a `:notes` key and value into the existing map.

````clojure
    user> (do-cmd :get-all-nodes :decrypt? true)
    {:result
     ({:modified "2023-04-04 20:31:04",
       :id 1,
       :parent :root,
       :key "facebook.com",
       :value
       {:user "myuserID",
	:pwd "myFacebookPwd",
	:notes "close this account"},
       :version 2,
       :created "2023-04-04 20:30:26"})}
````

Please see the `stow.api` namespace and underlying `stow.db` namespace for other useful functions.


## License

Copyright © 2023 Jude Payne

Distributed under the [MIT License](http://opensource.org/licenses/MIT)
