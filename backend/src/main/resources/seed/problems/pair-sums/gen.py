import sys, random
seed, n = int(sys.argv[1]), int(sys.argv[2])
r = random.Random(seed)
n = max(1, min(n, 100000))
top = r.choice([1, 50, 10**9])
a = [r.randint(-top, top) for _ in range(n)]
k = r.choice([a[0] + a[-1], 0, 2 * top, r.randint(-2 * top, 2 * top)])
if seed % 5 == 0:  # all values equal: n(n-1)/2 pairs, beyond the 32-bit range at the maximum n
    v = r.randint(-10**9, 10**9); a = [v] * n; k = 2 * v
k = max(-2 * 10**9, min(2 * 10**9, k))
print(n, k); print(*a)
