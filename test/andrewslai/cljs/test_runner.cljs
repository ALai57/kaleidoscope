(ns andrewslai.cljs.test-runner
  (:require [andrewslai.cljs.events.editor-test]
            [andrewslai.cljs.events.login-test]
            [andrewslai.cljs.core-test]
            [doo.runner :refer-macros [doo-tests]]))

(enable-console-print!)

(doo-tests 'andrewslai.cljs.core-test
           'andrewslai.cljs.events.login-test
           'andrewslai.cljs.events.editor-test)

