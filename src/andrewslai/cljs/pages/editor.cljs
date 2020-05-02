(ns andrewslai.cljs.pages.editor
  (:require [andrewslai.cljs.navbar :as nav]
            [andrewslai.cljs.events.editor :as ed]
            [goog.object :as gobj]
            [re-frame.core :refer [dispatch subscribe]]
            [reagent.core :as reagent]
            [cljsjs.slate-react]
            [cljsjs.slate]))


;; https://reactrocket.com/post/slatejs-basics/
(defn render-mark
  "Renders a slatejs mark to HTML."
  [props editor next]
  (let [attributes (.-attributes props)
        children (.-children props)
        mark (.-mark props)
        mark-type (.-type mark)]
    (case mark-type
      "bold"   (reagent/as-element [:strong (merge {:style {:font-weight "bold"}}
                                                   (js->clj attributes))
                                    children])
      "italic" (reagent/as-element [:em (js->clj attributes) children])
      ;; Else: call next to continue with slatejs' plugin stack.
      (next))))

;; (def is-hotkey-mod-b (npm.slatejs/isHotkey "mod+b"))
;; (def is-hotkey-mod-i (npm.slatejs/isHotkey "mod+i"))

(defn key-down-handler
  "Event callback for keyDown event."
  [event editor next]
  (.log js/console event)
  (.log js/console editor)
  (.log js/console next)
  (cond
    ;; (is-hotkey-mod-b event) (toggle-mark event "bold" editor)
    ;; (is-hotkey-mod-i event) (toggle-mark event "italic" editor)

    ;; Else: call next to continue with slatejs' plugin stack.
    :else (next)))

(defn blank-value
  "Returns a blank editor value."
  []
  (->> {:document {:nodes [{:object "block"
                            :type "paragraph"
                            :nodes [{:object "text"
                                     :leaves [{:text "Hello world! "}
                                              {:text "Bold "
                                               :marks [{:type "bold"}]}
                                              {:text "Italics "
                                               :marks [{:type "italic"}]}]}]}]}}
       clj->js
       (.fromJSON js/Slate.Value)))


;; https://github.com/jhund/re-frame-and-reagent-and-slatejs/blob/master/src/cljs/rrs/ui/slatejs/views.cljs
  Uses a form3 reagent component to manage React lifecycle methods."
(defn editor []
  (let [section-data (subscribe [:editor-data])
        html (:html @section-data)
        editor-atom (atom nil)
        editor-text (atom nil)

        update-editor-ref
        (fn [component]
          (when component (reset! editor-ref-atom component)))

        change-handler
        (fn [change-or-editor]
          (let [new-value (.-value change-or-editor)]
            (reset! editor-text new-value)
            (some-> @editor-atom reagent/force-update)
            (dispatch [:editor-text-changed new-value])))]

    (reagent/create-class
      {:display-name "slatejs-editor"
       :component-did-mount (fn [this] (reset! editor-atom this))
       :component-will-unmount (fn [this] (reset! editor-atom nil))
       :reagent-render (fn [_]
                         [:> js/SlateReact.Editor
                          {:auto-focus true
                           :class-name "slatejs-editor"
                           :id "slatejs-editor-instance-1"
                           :on-change change-handler
                           ;;:on-key-down key-down-handler
                           :render-mark render-mark
                           :ref update-editor-ref
                           :value (or @editor-text
                                      @section-data
                                      (blank-value))}])})))

(defn serialized-data []
  (let [section-data (subscribe [:editor-data])]
    (when @section-data
      [:p (str (ed/editor-model->clj @section-data))])))


(defn editor-ui []
  [:div
   [nav/primary-nav]
   [:br]
   [:h1 "Editor"]
   [:br]
   [:h5 "How text looks in an article"]
   [:div {:style {:border-style "double"}}
    [editor]]
   [:br] [:br]
   [:h5 "How text looks in the database"]
   [:div {:style {:border-style "ridge"}}
    [serialized-data]]])
