Precompute prefix sums `P[0] = 0`, `P[i] = P[i−1] + a_i`. Each query is then `P[r] − P[l−1]` in O(1), giving O(n + q) total. Values reach 10^14, so 32-bit accumulators overflow.
