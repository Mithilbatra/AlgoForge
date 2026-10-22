import sys, random
seed, n = int(sys.argv[1]), int(sys.argv[2])
r = random.Random(seed)
s = max(1, min(n, 50000))
k = r.randint(1, min(100, s))
coins = r.sample(range(1, min(s, 200) + 1), min(k, min(s, 200)))
print(len(coins), s); print(*coins)
