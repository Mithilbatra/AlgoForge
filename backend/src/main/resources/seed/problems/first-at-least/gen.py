import sys, random
seed, n = int(sys.argv[1]), int(sys.argv[2])
r = random.Random(seed)
n = max(1, min(n, 100000)); q = n
spread = r.choice([3, 1000, 10**9])
a = sorted(r.randint(-spread, spread) for _ in range(n))
qs = [r.randint(max(-10**9, -spread - 1), min(10**9, spread + 1)) for _ in range(q)]
print(n, q); print(*a); print("\n".join(map(str, qs)))
