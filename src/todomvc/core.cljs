(ns todomvc.core
  (:require-macros [secretary.core :refer [defroute]])
  (:require [goog.events :as events]
            [reagent.core :as reagent]
            [re-frame.core :refer [dispatch dispatch-sync]]
            [secretary.core :as secretary]
            [todomvc.events] ;; These two are only required to make the compiler
            [todomvc.subs]   ;; load them (see docs/App-Structure.md)
            [todomvc.views]
            [devtools.core :as devtools])
  (:import [goog History]
           [goog.history EventType]))


;; -- Debugging aids ----------------------------------------------------------
(devtools/install!)       ;; we love https://github.com/binaryage/cljs-devtools
(enable-console-print!)   ;; so that println writes to `console.log`


;; Put an initial value into app-db.
;; The event handler for `:initialise-db` can be found in `events.cljs`
;; Using the sync version of dispatch means that value is in
;; place before we go onto the next step.
(dispatch-sync [:initialise-db])

;; -- Routes and History ------------------------------------------------------

(defroute "/" [] (dispatch [:set-showing :all]))
(defroute "/:path" [path] (dispatch [:set-active-panel (keyword path)]))

;; For routing with the TODO application
#_(defroute "/:path" [path] (dispatch [:set-showing (keyword path)]))

(def history
  (doto (History.)
    (events/listen EventType.NAVIGATE
                   (fn [event] (secretary/dispatch! (.-token event))))
    (.setEnabled true)))


;; -- Entry Point -------------------------------------------------------------
;; Within ../../resources/public/index.html you'll see this code
;;    window.onload = function () {
;;      todomvc.core.main();
;;    }
;; So this is the entry function that kicks off the app once HTML is loaded

(defn ^:export main
  []
  ;; `todomvc.views/app` is the root view for the entire UI.
  (reagent/render [todomvc.views/app]
                  (.getElementById js/document "app")))
