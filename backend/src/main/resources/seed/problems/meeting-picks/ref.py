import sys
data = sys.stdin.buffer.read().split()
n = int(data[0])
talks = sorted(((int(data[2 + 2 * i]), int(data[1 + 2 * i])) for i in range(n)))
count, last = 0, -1
for e, s in talks:
    if s >= last:
        count += 1; last = e
print(count)
