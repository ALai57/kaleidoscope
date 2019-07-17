(ns andrewslai.loading
  (:require [reagent.core  :as reagent]
            [re-frame.core :refer [subscribe
                                   dispatch
                                   reg-sub]]
            [clojure.string :as str]

            [cljsjs.react-bootstrap]
            ["react" :as react]
            ["react-spinners" :as spinner]
            ["emotion" :as emotion]
            ["react-spinners/ClipLoader" :as cl-spinner]
            ))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Set default properties for spinners
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; TODO: Move to environment variable config?
(set! (.. cl-spinner -default -defaultProps -size) 150)
(set! (.. cl-spinner -default -defaultProps -color) "#4286f4")


;; Necessary to get the element to render properly
(extend-protocol IPrintWithWriter
  js/Symbol
  (-pr-writer [sym writer _]
    (-write writer (str "\"" (.toString sym) "\""))))


(defn get-elements-in-class [class-name]
  (.getElementsByClassName js/document class-name))

(defn remove-dynamic-js []
  (while (not (nil? (.item (get-elements-in-class "dynamicjs") 0)))
    (.remove (.item (get-elements-in-class "dynamicjs") 0))))

(defn load-screen []
  (let [loading? (subscribe [:loading?])
        spinner-proto (.. cl-spinner -default -prototype)]
    (set! (.. spinner-proto -constructor -defaultProps -loading)
          (js->clj @loading?))
    (if (true? @loading?) (remove-dynamic-js))
    [:div#loading.load-icon
     (.render spinner-proto)]))

(defn load-screen-test []
  (let [loading? (subscribe [:loading?])
        spinner-proto (.. cl-spinner -default -prototype)]
    (set! (.. spinner-proto -constructor -defaultProps -loading)
          (js->clj true))
    (if (true? @loading?) (remove-dynamic-js))
    [:div#loading.load-icon {:style {:text-align "center"
                                     :margin "auto"
                                     :width "100%"
                                     :position "relative"
                                     :top "50px"}}
     (.render spinner-proto)]))



