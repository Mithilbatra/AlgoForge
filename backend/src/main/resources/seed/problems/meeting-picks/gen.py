import sys, random
seed, n = int(sys.argv[1]), int(sys.argv[2])
r = random.Random(seed)
n = max(1, min(n, 100000))
span = r.choice([20, 10**9])
print(n)
for _ in range(n):
    s = r.randint(0, span - 1); print(s, r.randint(s + 1, min(span, s + max(1, span // 10))))
