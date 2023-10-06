(ns kaleidoscope.api.portfolio
  (:require [kaleidoscope.persistence.rdbms :as rdbms]))

(def PortfolioNode
  [:map
   [:id :int]
   [:name :string]
   [:url :string]
   [:image-url :string]
   [:description :string]
   [:tags :string]])

(def PortfolioLink
  [:map
   [:id :int]
   [:name-1 :string]
   [:relation :string]
   [:name-2 :string]
   [:description :string]])

(def Portfolio
  [:map
   [:links [:sequential PortfolioLink]]
   [:nodes [:sequential PortfolioNode]]])

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Private helpers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private get-nodes
  (rdbms/make-finder :portfolio-entries))

(def ^:private get-links
  (rdbms/make-finder :portfolio-links))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Public API
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn get-portfolio
  {:malli/schema [:=> [:cat :any]
                  Portfolio]
   :malli/scope  #{:output}}
  [database]
  {:nodes (get-nodes database)
   :links (get-links database)})
