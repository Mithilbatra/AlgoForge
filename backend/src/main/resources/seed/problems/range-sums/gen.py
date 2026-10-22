import sys, random
seed, n = int(sys.argv[1]), int(sys.argv[2])
r = random.Random(seed)
n = max(1, min(n, 100000)); q = n
top = r.choice([5, 10**9])
print(n, q); print(*(r.randint(-top, top) for _ in range(n)))
for _ in range(q):
    l = r.randint(1, n); print(l, r.randint(l, n))
