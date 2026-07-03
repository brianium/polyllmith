(ns user
  (:require [clj-reload.core :as reload]))

(reload/init
 {:dirs        ["components" "development" "bases"]
  :no-reload   '#{user}})

(defn dev []
  (require 'dev)
  (in-ns 'dev))
