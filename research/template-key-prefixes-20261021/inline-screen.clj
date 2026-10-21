;; Same fixed 3-arm screen, with a materially different generated loop.
(load-file "research/template-key-prefixes-20261021/inline.clj")
(with-redefs [prefix-native inline-native]
  (load-string
    (replace-once (slurp "research/template-key-prefixes-20261021/screen.clj")
                  "(load-file \"research/template-key-prefixes-20261021/prototype.clj\")" "")))
