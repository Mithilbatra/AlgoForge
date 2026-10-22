import sys, random
seed, n = int(sys.argv[1]), int(sys.argv[2])
r = random.Random(seed)
n = max(1, min(n, 100000)); m = n
print(n, m)
chain = seed % 2 == 0  # a long path built in order stresses naive linking without path compression
for i in range(m):
    if chain and i + 2 <= n: print(i + 1, i + 2)
    else: print(r.randint(1, n), r.randint(1, n))
