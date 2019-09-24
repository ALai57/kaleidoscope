(ns andrewslai.cljs.components.d3
  (:require [cljsjs.react-bootstrap]
            [cljsjs.d3]
            [cljsjs.react-pose]
            ["react" :as react]
            [goog.object :as gobj]))



;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; D3 helper functions
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn add-fade-to-class [m]
  (.. js/d3
      (select (:class m))
      (transition)
      (duration (:duration m))
      (style "opacity" (:opacity m))))

(defn set-class-properties [m]
  (.. js/d3
      (select ".tooltip")
      (html (:html m))
      (style "left" (str (:x m) "px"))
      (style "top" (str (:y m) "px"))))

(defn extract-commit-data [d]
  (let [{:keys [commits repo]} (js->clj d :keywordize-keys true)]
    (str "Repo: " repo "<br/>"
         "N commits: " commits)))

(defn show-tooltip [d]
  (let [x (.-pageX js/d3.event)
        y (.-pageY js/d3.event)]
    (add-fade-to-class {:class ".tooltip"
                        :duration 200
                        :opacity 0.9})
    (set-class-properties {:html (extract-commit-data d)
                           :x x
                           :y y})))

(defn hide-tooltip []
  (add-fade-to-class {:class ".tooltip"
                      :duration 200
                      :opacity 0}))

(defn create-elements [m]
  (.. js/d3
      (select "svg")
      (selectAll (:el m))
      (data (:data m))
      enter
      (append (:el m))))

(defn create-tooltip []
  (.. js/d3
      (select "body")
      (append "div")
      (attr "class" "tooltip")
      (style "opacity" 0)))
