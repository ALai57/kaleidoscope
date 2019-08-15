(ns andrewslai.cljs.d3
  (:require-macros [reagent.ratom :refer [reaction]])
  (:require [reagent.core :as reagent :refer [atom]]
            [re-frame.core :refer [register-handler
                                   path
                                   dispatch
                                   subscribe]]
            [cljsjs.d3]))

(defn d3-inner [data]
  (reagent/create-class
   {:reagent-render (fn [] [:div [:svg {:width 400 :height 300}]])

    :component-did-mount (fn []
                           (let [d3data (clj->js data)]
                             (.. js/d3
                                 (select "svg")
                                 (selectAll "circle")
                                 (data d3data)
                                 enter
                                 (append "circle")
                                 (attr "cx" (fn [d] (.-x d)))
                                 (attr "cy" (fn [d] (.-y d)))
                                 (attr "r" (fn [d] (.-r d)))
                                 (attr "fill" (fn [d] (.-color d))))))

    :component-did-update (fn [this]
                            (let [[_ data] (reagent/argv this)
                                  d3data (clj->js data)]
                              (.. js/d3
                                  (selectAll "circle")
                                  (data d3data)
                                  (attr "cx" (fn [d] (.-x d)))
                                  (attr "cy" (fn [d] (.-y d)))
                                  (attr "r" (fn [d] (.-r d)))))
                            )}))


(defn slider [param idx value]
  [:input {:type "range"
           :value value
           :min 0
           :max 500
           :style {:width "100%"}
           :on-change #(dispatch [:update-circles
                                  idx param (-> % .-target .-value)])}])

(defn sliders [data]
  [:div (for [[idx d] (map-indexed vector data)]
          ^{:key (str "slider-" idx)}
          [:div
           [:h3 (:name d)]
           "x " (:x d) (slider :x idx (:x d))
           "y " (:y d) (slider :y idx (:y d))
           "r " (:r d) (slider :r idx (:r d))])])

(defn d3-example []
  (let [data (subscribe [:circles])]
    [:div {:class "container"}
     "Hey there!"
     [:div {:class "row"}
      [:div {:class "col-md-5"}
       [d3-inner @data]]
      [:div {:class "col-md-5"}
       [sliders @data]]]]))
