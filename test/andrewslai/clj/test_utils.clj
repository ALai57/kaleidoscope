(ns andrewslai.clj.test-utils
  (:require [clojure.test :refer [deftest]]))

(defmacro defsitetest [name & body]
  `(deftest ~name
     (binding [andrewslai.clj.persistence.config/*live-db?* false]
       ~@body)))

(defmacro with-captured-input-as [captured-inputs capture-fn & body]
  `(let [~captured-inputs (atom [])
         ~capture-fn (fn [captured-fn# & inputs#]
                       (swap! ~captured-inputs conj {captured-fn# inputs#}))]
     ~@body))

(comment
  (macroexpand-1 '(defsitetest mytest
                    (testing "Hello")))

  (defsitetest mytest
    ("Hello"))
  
  )