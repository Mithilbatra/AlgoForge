import sys, random
seed, n = int(sys.argv[1]), int(sys.argv[2])
r = random.Random(seed)
n = max(1, min(n, 100000))
top = r.choice([1, 10**4])
print(n); print(*(r.randint(0, top) for _ in range(n)))
