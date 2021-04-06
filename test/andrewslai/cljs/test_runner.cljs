(ns andrewslai.cljs.test-runner
  (:require [andrewslai.cljs.events.editor-test]
            [andrewslai.cljs.events.articles-test]
            [andrewslai.cljs.core-test]
            [figwheel.main.testing :refer-macros [run-tests]]
            [cljs-test-display.core]))

(enable-console-print!)

(run-tests (cljs-test-display.core/init! "app-testing")
           'andrewslai.cljs.core-test
           'andrewslai.cljs.events.articles-test
           'andrewslai.cljs.events.editor-test)
