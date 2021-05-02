(ns andrewslai.cljs.test-runner
  (:require [andrewslai.cljs.core-test]
            [andrewslai.cljs.events.articles-test]
            [andrewslai.cljs.events.core-test]
            [andrewslai.cljs.events.editor-test]
            [andrewslai.cljs.events.projects-portfolio-test]
            [cljs-test-display.core]
            [figwheel.main.testing :refer-macros [run-tests]]))

(enable-console-print!)

(run-tests (cljs-test-display.core/init! "app-testing")
           'andrewslai.cljs.core-test
           'andrewslai.cljs.events.core-test
           'andrewslai.cljs.events.articles-test
           'andrewslai.cljs.events.editor-test
           'andrewslai.cljs.events.projects-portfolio-test)
