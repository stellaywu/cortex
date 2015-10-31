(ns thinktopic.cortex.network
  (:require [clojure.core.matrix :as mat]
            [clojure.core.matrix.linear :as linear]
            [mikera.vectorz.core :as vectorz]
            [thinktopic.datasets.mnist :as mnist]
            [thinktopic.cortex.util :as util]))

(mat/set-current-implementation :vectorz)

;; Neural Protocols

;; Gradient descent
;; 1) forward propagate
;;  * sum((weights * inputs) + biases) for each neuron, layer by layer
;; 2) back propagate deltas
;;  * After propagating forward, we need to figure out the error (often called the
;;    delta) for each neuron.  For the outputs this is just the
;;    ((output - expected_output) * activation-fn-prime), but for the hidden units the output
;;    error has to be propagated back across the weights to distribute the error according to
;;    which hidden units were responsible for it.
;; 3) compute gradients
;;  * Then for each weight you multiply its output delta by its input activation to get
;;    its gradient.  This will correspond to the direction and magnitude of the
;;    error for that weight, so any update should happen in the opposite direction.
;; 4) update weights
;;  * multiply gradient by the learning-rate to smooth out jitter in updates,
;;    and subtract from the weights

(defprotocol NeuralLayer
  "A basic neural network layer abstraction supporting forward and backward propagation of
  input activations and error gradients."
  (forward [this input]
           "Pass data into the layer and return its output.  Also set the :output
           key with the output values for later retrieval.")

  (backward [this input output-gradient]
            "Back propagate errors through the layer with respect to the input.  Returns the
            input deltas (gradient at the inputs).
            NOTE: the input passed in must be the same input that was used in the forward pass."))

(defprotocol ParameterLayer
  "For layers that have trainable parameters extend this protocol in order to expose the parameters
  and accumulated gradients to optimization algorithms."
  (parameters-gradients [this]
    "Returns a vector of [[params gradients] ...] pairs."))

;; possibly two steps to backpropagation per layer:
;; * compute gradient with respect to the inputs
;;  - multiply by weights transpose, or by derivative of activation function
;; * compute gradient with respect to the parameters of a module (weights & biases)
;;  1) start with zeroed out gradients
;;  2) accumulate the gradients over a mini-batch
;;  3) update parameters using average of accumulated gradients (* grads (/ 1 batch-size)
;;      -> params = params - (learning-rate * param-gradients)
;;  4) zero out parameter gradients

(defprotocol LossFn
  (loss [this v target])
  (delta [this v target]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Activation Functions
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

; NOTE: This form of computing a sigmoid over a matrix is faster than mapping
; over each individual element.
(defn sigmoid
  "y =  1 / (1 + e^(-z))
  Produces an output between 0 and 1."
  [z]
  (mat/div! (mat/add! (util/exp! (mat/negate z)) 1.0)))

(defn sigmoid!
  "y =  1 / (1 + e^(-z))
  Produces an output between 0 and 1."
  [z]
  (mat/div! (mat/add! (util/exp! (mat/negate! z)) 1.0)))

(defn sigmoid'
  [z]
  (let [sz (sigmoid z)]
    (mat/emul sz (mat/sub 1.0 sz))))

(defrecord SigmoidActivation [output input-gradient]
  NeuralLayer
  (forward [this input]
    (mat/assign! output input)
    (sigmoid! output))

  (backward [this input output-gradient]
    (mat/assign! input-gradient output-gradient)
    (mat/emul! input-gradient output (mat/sub 1.0 output))))

(defmethod print-method SigmoidActivation [x ^java.io.Writer writer]
  (print-method (format "Sigmoid %s" (mat/shape (:output x))) writer))

(defn sigmoid-activation
  [shape]
  (let [shape (if (number? shape) [1 shape] shape)]
    (map->SigmoidActivation {:output (mat/zero-array shape)})))

(defrecord RectifiedLinearActivation [output input-gradient]
  NeuralLayer
  (forward [this input]
    (mat/assign! output input)
    (mat/emap! #(if (neg? %) 0 %) output))

  (backward [this input output-gradient]
    (mat/assign! input-gradient output-gradient)
    (mat/emap! #(if (neg? %) 0 %) input-gradient)))

(defn softmax
  "Used for multinomial classification (choose 1 of n classes), where the output
  layer can be interpreted as class probabilities."
  [z]
  (mat/div z (mat/esum z)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Loss Functions
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn mean-squared-error
  [activation target]
  (mat/div (mat/esum (mat/pow (mat/sub activation target) 2))
           (mat/ecount activation)))

(deftype MSELoss []
  LossFn
  (loss [this v target]
    (let [z (mat/sub v target)]
      (mat/esum (mat/pow z 2))))

  (delta [this v target]
    (mat/sub v target)))

; NOTE: not really sure how this is supposed to differ and why from a simple MSE
; loss.  Must investigate.
(deftype QuadraticLoss []
  LossFn
  (loss [this v target]
    ; NOTE: linear/norm is different for matrices and vectors so this row-matrix
    ; conversion is important for correctness.
    (let [diff (mat/sub v target)
          diff (if (mat/vec? diff) (mat/row-matrix diff) diff)]
      (mat/mul 0.5 (mat/pow (linear/norm diff) 2))))

  (delta [this v target]
    (mat/sub v target)))

(defn quadratic-loss
  []
  (QuadraticLoss.))

(def SMALL-NUM 1e-30)

(deftype CrossEntropyLoss []
  LossFn
  (loss [this activation target]
    (let [a (mat/mul (mat/negate target) (util/log (mat/add SMALL-NUM activation)))
          b (mat/mul (mat/sub 1.0 target) (util/log (mat/sub (+ 1.0 SMALL-NUM) a)))
          c (mat/esum (mat/sub a b))]
      c))

  (delta [this v target]
    (mat/sub v target)))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Layer Types
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;


(defrecord LinearLayer [n-inputs n-outputs
                        weights biases output
                        weight-gradient bias-gradient input-gradient]
  NeuralLayer
  (forward [this input]
    (mat/assign! output biases)
    (mat/add! output (mat/mmul input (mat/transpose weights))))

  ; Compute the error gradients with respect to the input data by multiplying the
  ; output errors backwards through the weights, and then compute the error
  ; with respect to the weights by multiplying the input error times the
  ; output error.

  ; NOTE: this accumulates the bias and weight gradients, but the optimizer is
  ; expected to apply the gradients to the parameters and then zero them out
  ; after each mini batch.
  (backward [this input output-gradient]
    (let [input-grad (mat/mmul (mat/transpose weights) output-gradient)]
      (mat/add! bias-gradient output-gradient)
      (mat/add! weight-gradient (mat/outer-product output-gradient input-grad))
      (mat/assign! input-gradient input-grad)))

  ParameterLayer
  (parameters-gradients [this]
    [[weights weight-gradient]
     [biases bias-gradient]]))

(defmethod print-method LinearLayer [x ^java.io.Writer writer]
  (print-method (format "Linear [%d %d]" (:n-inputs x) (:n-outputs x)) writer))

; TODO: Define this for an EDN serializable version, and another one for nippy.
;(defmethod print-dup LinearLayer [x ^java.io.Writer writer]
;  (print-dup (:a x) writer))

(defn linear-layer
  [& {:keys [n-inputs n-outputs]}]
  (let [weights (util/weight-matrix n-outputs n-inputs)
        biases (util/rand-matrix 1 n-outputs)]
    (map->LinearLayer
      {:n-inputs n-inputs
       :n-outputs n-outputs
       :weights weights
       :biases biases
       :output (mat/zero-array (mat/shape biases))
       :weight-gradient (mat/zero-array (mat/shape weights))
       :bias-gradient (mat/zero-array (mat/shape biases))
       :input-gradient (mat/zero-array [1 n-inputs])})))

(defrecord IdentityLayer []
  NeuralLayer
  (forward [this input] input)
  (backward [this input output-gradient] output-gradient))

(defn identity-layer
  []
  (IdentityLayer.))

(defrecord SequentialNetwork [layers]
  NeuralLayer
  (forward [this input]
    (println "forward:")
    (reduce (fn [activation layer]
              (println "\t" layer)
              (forward layer activation))
            input layers))

  (backward [this input output-gradient]
    (reduce (fn [out-grad [prev-layer layer]]
              (backward layer (:output prev-layer) out-grad))
            output-gradient
            (map vector (reverse layers)
                 (concat (next (reverse layers)) [{:output input}]))))

 ParameterLayer
 (parameters-gradients [this]
    (mapcat #(if (extends? ParameterLayer (type %))
               (params-gradients %)
               [])
            layers)))

(defmethod print-method SequentialNetwork [x ^java.io.Writer writer]
  (print-method (format "Sequential Network [%d layers]" (count (:layers x))) writer))

(defn sequential-network
  [layers]
  (SequentialNetwork. layers))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Training and Optimization
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defprotocol )

; An optimizer takes:
; - network
; - loss function
; - regularizers  (get passed the network each iteration, result added to loss)
; - optimization params
;
; returns a function that takes an input and a label, returns runtime stats and
; a new network.
; -
(defn sgd-optimizer
  [net loss-fn {:keys [learning-rate batch-size loss-fn] :as opts}]
  (let [trainer (fn [state input label]
                  (let [start-time (util/timestamp)
                        output (forward net input)
                        forward-time (util/ms-elapsed start-time (util/timestamp))

                        loss (loss loss-fn output label)
                        loss-delta (delta loss-fn output label)

                        start-time (util/timestamp)
                        gradient (backward net input loss-delta)
                        backward-time (util/ms-elapsed start-time (util/timestamp))

                        iteration (inc (:iteration state))
                        state (assoc state :iteration iteration
                                     :forward-time forward-time
                                     :backward-time backward-time
                                     :loss loss)]
                    (if (zero? (mod iteration batch-size))
                      (let [params-grads (parameters-gradients net)
                            ; gradient = accumulated-gradient / batch-size

                            ; Vanilla SGD
                            ; param += - learning-rate * gradient

                            ; SGD + Momentum
                            ; dx = (prev-dx * momentum) + learning-rate * gradient
                            ; prev-dx = dx
                            ; param += dx
                            ]
                        (doseq [[params grads] params-grads]
                          (mat/sub! params (mat/mul learning-rate (mat/div! grads batch-size))))
                        state)
                      state)))]
    [trainer {:iteration 0}]))

(defn train-network
  [optimizer n-epochs batch-size data labels]
  (loop [i 0]
    (if (= i n-epochs)
      state)))

(defn confusion-matrix
  "A confusion matrix shows the predicted classes for each of the actual
  classes in order to understand performance and commonly confused classes.

                         Predicted
                       Cat Dog Rabbit
               | Cat	   5  3  0
        Actual | Dog	   2  3  1
               | Rabbit  0  2  11

  Initialize with a set of string labels, and then call add-prediction for
  each prediction to accumulate into the matrix."
  [labels]
  (let [prediction-map (zipmap labels (repeat 0))]
    (into {} (for [label labels]
               [label prediction-map]))))

(defn add-prediction
  [conf-mat prediction label]
  (update-in conf-mat [label prediction] inc))

(defn print-confusion-matrix
  [conf-mat]
  (let [ks (sort (keys conf-mat))
        label-len (inc (apply max (map count ks)))
        prefix (apply str (repeat label-len " "))
        s-fmt (str "%" label-len "s")]
    (apply println prefix ks)
    (doseq [k ks]
      (apply println (format s-fmt k) (map #(get-in conf-mat [k %]) ks)))))