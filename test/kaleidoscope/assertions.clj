(ns kaleidoscope.assertions
  (:require [clojure.test :as t]
            [clojure.test.check.clojure-test.assertions :as a]))


;; Some notes on how defspec works
;; `defspec` calls `quick-check` to produce a map, then asserts against the map
;; using the multimethod clojure.test/assert-expr with a custom implementation
;; that is installed when test.check is required.
;;
;; The custom `assert-expr` method (`check?`) reports using `t/do-report`
;; and relies on existing implementations of the `report` multimethod to report
;; failures. In order to change the failure behavior, we need to override the
;; assert-expr method!


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Patch the `clojure.test/is` macro and override existing support for the
;; `assert-expr` for `clojure.test.check.clojure-test/check?` function.
;; Normally, this `check?` is used to perform `defspec`'s assertions and reporting.
;; However, the problem is that the built-in reporting delegates to
;; `clojure.test`'s `:fail` type reporter, which is too verbose and
;; doesn't quite match the generative testing use case.
;; ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn test-context-stacktrace [st]
  (drop-while
   #(let [class-name (.getClassName ^StackTraceElement %)]
      (or (.startsWith class-name "java.lang")
          (.startsWith class-name "clojure.test$")
          (.startsWith class-name "clojure.test.check.clojure_test$")
          (.startsWith class-name "clojure.test.check.clojure_test.assertions")
          (.startsWith class-name "kaleidoscope.assertions")))
   st))

(defn check-results
  [m]
  (if (:pass? m)
    (t/do-report
     {:type :pass
      :message (dissoc m :result)})
    (t/do-report
     (merge {:type :spec-fail
             :starting (:fail m)
             :smallest (get-in m [:shrunk :smallest])}
            (a/file-and-line*
             (test-context-stacktrace (.getStackTrace (Thread/currentThread))))))))

(defn check?
  "This isn't just directly inlined so that it emits a form and we can
  preserve line numbering during testing."
  [_ form]
  `(let [m# ~(nth form 1)]
     (check-results m#)))

(defmethod t/assert-expr 'clojure.test.check.clojure-test/check?
  [_ form]
  (check? _ form))

;; Patch the clojure test reporter to include a new reporter for Specs
;; This allows us to get cleaner logging/error messaging when a spec fails
(defmethod t/report :spec-fail
  [m]
  (t/with-test-out
    (t/inc-report-counter :fail)
    (println "\nFAIL in" (t/testing-vars-str m))
    (println "GENERATED VALUES DO NOT SATISFY SPEC")
    (when (seq t/*testing-contexts*) (println (t/testing-contexts-str)))
    (when-let [message (:message m)] (println message))
    (println "smallest:" (pr-str (:smallest m)))
    (println "starting:" (pr-str (:starting m)))))

(comment
  (methods t/assert-expr)
  )
