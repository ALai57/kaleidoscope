(ns andrewslai.cljs.pose
  (:require [andrewslai.cljs.article :as article]
            [andrewslai.cljs.article-cards :as cards]
            [andrewslai.cljs.circle-nav :as circle-nav]
            [andrewslai.cljs.loading :as loading]
            [andrewslai.cljs.navbar :as nav]
            [reagent.core  :as reagent]
            [re-frame.core :refer [subscribe
                                   dispatch
                                   reg-sub]]

            [ajax.core :refer [GET]]
            [ajax.protocols :as pr]
            [ajax.ring :refer [ring-response-format]]
            [cljsjs.react-bootstrap]
            [cljsjs.react-transition-group :as rtg]
            [cljsjs.d3]
            ["react" :as react]
            ["react-spinners" :as spinner]
            ["emotion" :as emotion]
            [cljsjs.react-pose]
            [goog.object :as gobj]
            [reframe-components.recom-radial-menu :as rcm]
            [stylefy.core :refer [init]]))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Testing
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(def PoseGroup (reagent/adapt-react-class js/PoseGroup))
(def PosedLi (reagent/adapt-react-class
              (js/posed.li (clj->js {:enter {:opacity 0.2
                                             :y "100px"}
                                     :before {:opacity 1
                                              :y "10px"}}))))

(def lst (atom [[:li "Hello"]
                [:li "Bye"]]))

(defn make-item [x k]
  ^{:key k}
  x)

(swap! lst conj [:li "Wh"])

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn pose
  []
  [:ul
   [PoseGroup {:animateOnMount true :preEnterPose "before"}
    [PosedLi "HiHi"]
    (map make-item @lst [1 2])]])

