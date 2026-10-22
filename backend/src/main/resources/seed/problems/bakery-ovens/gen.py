import sys, random
seed, n = int(sys.argv[1]), int(sys.argv[2])
r = random.Random(seed)
n = max(1, min(n, 100000))
top = r.choice([2, 1000, 10**9])
k = r.choice([1, 10**9, r.randint(1, 10**9)])
if seed % 5 == 0:  # fast ovens and a huge k: a careless upper bound overflows 64-bit tray counts
    top, k = 2, 10**9
print(n, k); print(*(r.randint(1, top) for _ in range(n)))
