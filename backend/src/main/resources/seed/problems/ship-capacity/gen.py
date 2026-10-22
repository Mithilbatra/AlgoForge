import sys, random
seed, n = int(sys.argv[1]), int(sys.argv[2])
r = random.Random(seed)
n = max(1, min(n, 100000))
d = r.choice([1, n, r.randint(1, n)])
top = r.choice([1, 10, 10**4])
print(n, d); print(*(r.randint(1, top) for _ in range(n)))
