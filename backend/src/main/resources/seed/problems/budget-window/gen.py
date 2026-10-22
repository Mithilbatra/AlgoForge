import sys, random
seed, n = int(sys.argv[1]), int(sys.argv[2])
r = random.Random(seed)
n = max(1, min(n, 100000))
top = r.choice([3, 10**4])
c = [r.randint(1, top) for _ in range(n)]
b = r.choice([1, r.randint(1, top * 20), 10**9])
print(n, b); print(*c)
