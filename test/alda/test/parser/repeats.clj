(ns alda.test.parser.repeats
  (:require [clojure.test :refer :all]
            [alda.test.helpers :refer (test-parse)]))

(deftest repeat-tests
  (testing "repeats"
    (is (= (test-parse :repeat "c1(~1)x7") 
           '(alda.lisp/repeat "this probably won't work...")))))
