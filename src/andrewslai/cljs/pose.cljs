(ns andrewslai.cljs.pose
  (:require [andrewslai.cljs.article :as article]
            [andrewslai.cljs.article-cards :as cards]
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
            ["react" :as react]
            ["react-spinners" :as spinner]
            ["@emotion/core" :as emotion]
            [cljsjs.react-pose]
            [goog.object :as gobj]
            ))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Testing
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(def PoseGroup (reagent/adapt-react-class js/PoseGroup))
(def PosedLi (reagent/adapt-react-class
              (js/posed.li (clj->js {:enter {:opacity 0.5
                                             ;;:y "100px"
                                             }
                                     :before {:opacity 1
                                              ;;:y "10px"
                                              }}))))



(def lst (reagent/atom ["HiHi"
                        "ByeBye"
                        "What's Up?"]))

(defn make-item [x]
  ^{:key x} [PosedLi x])

(comment
  (swap! lst conj [PosedLi "My Int " (rand 10)])
  js/popmotion
  )

(defn rnd-int []
  (str "My Num " (rand 10)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn pose []
  [:div
   [:button {:style {:color "black"
                     :background-color "red"}
             :onClick (fn [x] (swap! lst shuffle))} "Reorder list"]
   [:button
    {:style {:color "white"
             :background-color "blue"}
     :onClick (fn [x] (swap! lst conj (rnd-int)))} "Add Item"]
   [:ul
    [PoseGroup {:animateOnMount true :preEnterPose "before"}
     (map make-item @lst)]]])
