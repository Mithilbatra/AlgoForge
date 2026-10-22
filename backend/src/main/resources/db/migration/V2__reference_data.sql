insert into problem_sources (code, name, homepage) values
    ('ORIGINAL',   'AlgoForge Originals', null),
    ('CODEFORCES', 'Codeforces',          'https://codeforces.com'),
    ('LEETCODE',   'LeetCode',            'https://leetcode.com'),
    ('CODECHEF',   'CodeChef',            'https://www.codechef.com'),
    ('ATCODER',    'AtCoder',             'https://atcoder.jp'),
    ('CSES',       'CSES Problem Set',    'https://cses.fi/problemset'),
    ('HACKERRANK', 'HackerRank',          'https://www.hackerrank.com'),
    ('MANUAL',     'Other (manual link)', null);

insert into concepts (code, name, description, sort_order) values
    ('arrays',          'Arrays & strings',          'Indexing, scanning and in-place manipulation of sequences.', 1),
    ('hashing',         'Hashing',                   'Hash maps and sets for O(1) expected lookups and counting.', 2),
    ('two-pointers',    'Two pointers',              'Two indices moving monotonically through a sequence.', 3),
    ('sliding-window',  'Sliding window',            'Maintaining an aggregate over a moving contiguous window.', 4),
    ('sorting',         'Sorting',                   'Ordering data to expose structure; custom comparators.', 5),
    ('binary-search',   'Binary search',             'Searching sorted data and monotone predicates, including search on the answer.', 6),
    ('prefix-sums',     'Prefix sums',               'Precomputed cumulative sums for O(1) range queries.', 7),
    ('greedy',          'Greedy algorithms',         'Locally optimal choices justified by exchange arguments.', 8),
    ('recursion',       'Recursion & backtracking',  'Self-similar decomposition and exhaustive search with pruning.', 9),
    ('linked-lists',    'Linked lists',              'Pointer manipulation over node chains.', 10),
    ('stacks-queues',   'Stacks & queues',           'LIFO/FIFO structures, monotonic stacks, BFS queues.', 11),
    ('trees',           'Trees & BSTs',              'Traversals, recursion on trees, binary search trees.', 12),
    ('heaps',           'Heaps',                     'Priority queues for repeated min/max extraction.', 13),
    ('graph-traversal', 'Graph traversal',           'BFS and DFS over explicit and implicit graphs.', 14),
    ('shortest-paths',  'Shortest paths',            'Dijkstra, 0-1 BFS, Bellman-Ford.', 15),
    ('dsu',             'Disjoint set union',        'Union-find with path compression and union by size.', 16),
    ('dp',              'Dynamic programming',       'Optimal substructure with memoised or tabulated states.', 17),
    ('bit-manipulation','Bit manipulation',          'Masks, XOR tricks, subset enumeration.', 18),
    ('tries',           'Tries',                     'Prefix trees over strings or bits.', 19),
    ('segment-trees',   'Segment & Fenwick trees',   'Range queries with point or range updates.', 20),
    ('number-theory',   'Number theory',             'GCD, modular arithmetic, sieves, combinatorics.', 21),
    ('advanced-graphs', 'Advanced graph algorithms', 'MST, topological order, SCC, flows.', 22);

insert into concept_prerequisites (concept_code, requires) values
    ('hashing', 'arrays'), ('two-pointers', 'arrays'), ('sliding-window', 'two-pointers'),
    ('sorting', 'arrays'), ('binary-search', 'sorting'), ('prefix-sums', 'arrays'),
    ('greedy', 'sorting'), ('recursion', 'arrays'), ('linked-lists', 'arrays'),
    ('stacks-queues', 'arrays'), ('trees', 'recursion'), ('heaps', 'trees'),
    ('graph-traversal', 'stacks-queues'), ('graph-traversal', 'recursion'),
    ('shortest-paths', 'graph-traversal'), ('shortest-paths', 'heaps'), ('dsu', 'graph-traversal'),
    ('dp', 'recursion'), ('bit-manipulation', 'arrays'), ('tries', 'trees'), ('tries', 'hashing'),
    ('segment-trees', 'prefix-sums'), ('segment-trees', 'trees'), ('number-theory', 'arrays'),
    ('advanced-graphs', 'shortest-paths'), ('advanced-graphs', 'dsu');

-- Models are configuration, not code: change them here (or with UPDATE) without a redeploy. API keys stay in env vars.
insert into ai_provider_configs (provider, enabled, model, roles) values
    ('claude', true, 'claude-opus-5-5', '{REVIEW,TESTGEN}'),
    ('gemini', true, 'gemini-3.8-flash', '{REVIEW,TESTGEN}');

insert into oa_definitions (title, description, is_template, recipe, duration_minutes, mode, partial_credit, wrong_penalty_pct, allow_hints) values
    ('Internship coding round', 'Practice simulation: one easy and one medium problem in 60 minutes. Not a reproduction of any company''s assessment.',
        true, '{"slots": [{"difficulty": "EASY", "points": 100}, {"difficulty": "MEDIUM", "points": 200}]}', 60, 'ASSESSMENT', true, 0, false),
    ('General placement OA', 'Practice simulation: easy, medium and hard problems in 90 minutes with partial credit.',
        true, '{"slots": [{"difficulty": "EASY", "points": 100}, {"difficulty": "MEDIUM", "points": 200}, {"difficulty": "HARD", "points": 300}]}', 90, 'ASSESSMENT', true, 0, false),
    ('Competitive programming sprint', 'All-or-nothing scoring with a 10% penalty per rejected submission, contest style.',
        true, '{"slots": [{"difficulty": "EASY", "points": 100}, {"difficulty": "MEDIUM", "points": 200}, {"difficulty": "MEDIUM", "points": 200}, {"difficulty": "HARD", "points": 300}]}', 120, 'ASSESSMENT', false, 10, false);
