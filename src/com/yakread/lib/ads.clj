(ns com.yakread.lib.ads)

(defn active? [{:ad/keys [approve-state
                          paused
                          payment-failed
                          payment-method
                          recent-cost
                          budget]}]
  (and (= :approved approve-state)
       (not paused)
       (not payment-failed)
       (some? payment-method)
       (< recent-cost budget)))
