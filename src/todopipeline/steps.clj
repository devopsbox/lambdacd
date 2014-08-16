(ns todopipeline.steps
  (:require [lambdaci.shell :as shell]
            [lambdaci.dsl :as dsl]))

(defn in-cwd [specified-working-directory & steps]
  (fn [_]
    (dsl/execute-steps steps {:cwd specified-working-directory })))


;; ----------------------------------

(defn client-package [{cwd :cwd}]
  (shell/bash cwd
    "bower install"
    "./package.sh"
    "./publish.sh"))

(defn server-test [{cwd :cwd}]
  (shell/bash cwd
    "lein test"))

(defn server-package [{cwd :cwd}]
  (shell/bash cwd
    "lein uberjar"
    "./publish.sh"))

(defn server-deploy-ci [{cwd :cwd}]
  (shell/bash cwd "./deploy.sh backend_ci /tmp/mockrepo/server-snapshot.tar.gz"))

(defn client-deploy-ci [{cwd :cwd}]
  (shell/bash cwd "./deploy.sh frontend_ci /tmp/mockrepo/client-snapshot.tar.gz"))
