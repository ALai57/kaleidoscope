(ns andrewslai.cljs.pages.editor
  (:require [andrewslai.cljs.navbar :as nav]
            [andrewslai.cljs.events.editor :as ed]
            [goog.object :as gobj]
            [re-frame.core :refer [dispatch subscribe]]
            [reagent.core :as reagent]
            [cljsjs.slate-react]
            [cljsjs.slate]))


;; https://github.com/jhund/re-frame-and-reagent-and-slatejs/blob/master/src/cljs/rrs/ui/slatejs/views.cljs
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
      (next))))

(defn render-node
  "Renders a slatejs node to HTML."
  [props]
  #_(.log js/console props)
  (let [attributes (.-attributes props)
        node (.-node props)
        children (.-children props)
        node-type (.-type node)]
    (case node-type
      "code" (reagent/as-element [:pre (js->clj attributes)
                                  [:code children]])
      "code-block" (reagent/as-element [:pre (merge {:style {:white-space "pre-wrap"
                                                             :background "hsl(30,80%,90%)"}}
                                                    (js->clj attributes))
                                        [:code children]])
      (reagent/as-element [:p (js->clj attributes) children]))))

(defn toggle-mark
  "Toggles `mark-type` in editor's current selection."
  [event mark-type change editor]
  (let [props (.-props editor)
        on-change (.-onChange props)
        updated-editor (.toggleMark change mark-type)]
    (.preventDefault event)
    (on-change updated-editor)))

(defn insert-new-line
  [event change editor]
  (let [props (.-props editor)
        on-change (.-onChange props)
        updated-editor (.insertText change "\n")]
    (.preventDefault event)
    (on-change updated-editor)))

(defn ctrl-b? [event]
  (and event.ctrlKey (= event.keyCode 66)))

(defn ctrl-i? [event]
  (and event.ctrlKey (= event.keyCode 73)))

(defn ctrl-enter? [event]
  (and event.ctrlKey (= event.keyCode 13)))

;; https://www.strilliant.com/2018/07/15/let%E2%80%99s-build-a-fast-slick-and-customizable-rich-text-editor-with-slate-js-and-react/ 
;; https://stackoverflow.com/questions/56081674/enter-and-backspace-not-working-with-slate-js-editor-in-react 
(defn key-down-handler
  "Event callback for keyDown event."
  [event change editor]
  ;; (.log js/console "Event " event.ctrlKey event.keyCode)
  ;; (.log js/console "Change " change)
  ;; (.log js/console "Editor" editor)
  (cond
    (ctrl-b? event) (toggle-mark event "bold" change editor)
    (ctrl-i? event) (toggle-mark event "italic" change editor)
    (ctrl-enter? event) (insert-new-line event change editor)))

(defn blank-value
  "Returns a blank editor value."
  []
  (->> {:object "value"
        :document {:object "document"
                   :nodes [{:object "block"
                            :type "paragraph"
                            :nodes [{:object "text"
                                     :leaves [{:object "leaf"
                                               :text "Hello world! "}
                                              {:object "leaf"
                                               :text "Bold "
                                               :marks [{:type "bold"}]}
                                              {:object "leaf"
                                               :text "Italics "
                                               :marks [{:type "italic"}]}]}]}
                           {:object "block"
                            :type "code-inline"
                            :nodes [{:object "text"
                                     :leaves [{:object "leaf"
                                               :text "Code inline"}]}]}
                           {:object "block"
                            :type "code-block"
                            :nodes [{:object "text"
                                     :leaves [{:object "leaf"
                                               :text "Code block\n Hello"}]}]}
                           {:object "block"
                            :type "code"
                            :nodes [{:object "text"
                                     :leaves [{:object "leaf"
                                               :text "Code block"}]}]}]}}
       clj->js
       (.fromJSON js/Slate.Value)))


(defonce editor-ref-atom (atom nil))
(defn editor []
  (let [section-data (subscribe [:editor-data])
        html (:html @section-data)

        this-editor (atom nil)
        editor-text (atom nil)

        update-editor-ref
        (fn [component]
          (when component (reset! editor-ref-atom component)))

        change-handler
        (fn [change-or-editor]
          (let [new-value (.-value change-or-editor)]
            (reset! editor-text new-value)
            (some-> @this-editor reagent/force-update)
            (dispatch [:editor-text-changed new-value])))]

    (reagent/create-class
      {:display-name "slatejs-editor"
       :component-did-mount (fn [this] (reset! this-editor this))
       :component-will-unmount (fn [this] (reset! this-editor nil))
       :reagent-render (fn [_]
                         [:> js/SlateReact.Editor
                          {:auto-focus true
                           :class-name "slatejs-editor"
                           :id "slatejs-editor-instance-1"
                           :on-change change-handler
                           :on-key-down key-down-handler
                           :render-mark render-mark
                           :render-node render-node
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
