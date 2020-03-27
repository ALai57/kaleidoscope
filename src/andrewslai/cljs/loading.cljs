(ns andrewslai.cljs.loading
  (:require [reagent.core  :as reagent]
            [re-frame.core :refer [subscribe
                                   dispatch
                                   reg-sub]]
            [clojure.string :as str]
            [cljsjs.react-bootstrap]
            ["react" :as react]
            ["react-spinners" :as spinner]
            ["@emotion/core" :as emotion]
            ["react-spinners/ClipLoader" :as cl-spinner]
            ))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Set default properties for spinners
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; TODO: Move to environment variable config?
(set! (.. cl-spinner -default -defaultProps -size) 150)
(set! (.. cl-spinner -default -defaultProps -color) "#4286f4")

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Helper functions
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Necessary to get the element to render properly
(extend-protocol IPrintWithWriter
  js/Symbol
  (-pr-writer [sym writer _]
    (-write writer (str "\"" (.toString sym) "\""))))

(defn get-elements-in-class [class-name]
  (.getElementsByClassName js/document class-name))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Rendering
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn remove-dynamic-js []
  (let [get-first-item
        (fn [] (.item (get-elements-in-class "dynamicjs") 0))]

    (while (not (nil? (get-first-item)))
      (.remove (get-first-item)))))

(defn load-screen []
  [:div "Loading"]
  #_(let [loading? (subscribe [:loading?])
          spinner (.. cl-spinner -default -prototype)
          spinner-props (.. spinner -constructor -defaultProps)]

      (set! (.. spinner-props -loading) (js->clj @loading?))
      (if (true? @loading?) (remove-dynamic-js))
      [:div#loading.load-icon (.render spinner)]))

(defn load-screen-test []
  (let [loading? (subscribe [:loading?])
        spinner (.. cl-spinner -default -prototype)]
    (set! (.. spinner -constructor -defaultProps -loading)
          (js->clj true))
    (if (true? @loading?) (remove-dynamic-js))
    [:div#loading.load-icon (.render spinner)]))

#_{:style {:text-align "center"
           :margin "auto"
           :width "100%"
           :position "relative"
           :top "50px"}}



