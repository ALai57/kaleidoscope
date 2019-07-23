(ns andrewslai.cljs.core
  (:require-macros [secretary.core :refer [defroute]])
  (:require [goog.events :as events]
            [reagent.core :as reagent]
            [re-frame.core :refer [dispatch dispatch-sync]]
            [secretary.core :as secretary]
            [andrewslai.cljs.events] ;; required to make the compiler
            [andrewslai.cljs.subs]   ;; load them (see docs/App-Structure.md)
            [andrewslai.cljs.views]
            [devtools.core :as devtools])
  (:import [goog History]
           [goog.history EventType]))


;; -- Debugging aids ----------------------------------------------------------
(devtools/install!)       ;; https://github.com/binaryage/cljs-devtools
(enable-console-print!)   ;; so that println writes to `console.log`

;; -- Routes and History ------------------------------------------------------

(defroute "/" []
  (dispatch [:set-active-panel :home]))
(defroute "/:path" [path]
  (dispatch [:set-active-panel (keyword path)]))
(defroute "/:path/content/:content-name" [path content-name]
  (dispatch [:retrieve-content (keyword path) (keyword content-name)]))


(def history
  (doto (History.)
    (events/listen EventType.NAVIGATE
                   (fn [event] (secretary/dispatch! (.-token event))))
    (.setEnabled true)))


;; -- Entry Point -------------------------------------------------------------
;; Within ../../resources/public/index.html you'll see this code
;;    window.onload = function () {
;;      andrewslai.cljs.core.main();
;;    }
;; So this is the entry function that kicks off the app once HTML is loaded

(defn ^:export main
  []
  ;; `andrewslai.cljs.views/app` is the root view for the entire UI.
  (reagent/render [andrewslai.cljs.views/app]
                  (.getElementById js/document "app")))
