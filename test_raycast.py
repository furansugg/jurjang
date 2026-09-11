def trace(cx, cy, tx, ty, left, top, right, bottom, bounces=2):
    dx, dy = tx - cx, ty - cy
    length = (dx * dx + dy * dy) ** 0.5
    if length == 0: return []
    dx /= length; dy /= length
    hits = []
    x = max(left, min(right, tx))
    y = max(top, min(bottom, ty))
    for _ in range(bounces):
        min_t, side = float('inf'), 0
        if dx < -1e-4:
            t = (left - x) / dirX if (dirX := dx) else 0
            if t > 0.01 and t < min_t:
                test_y = y + t * dy
                if top - 2 <= test_y <= bottom + 2: min_t, side = t, 1
        elif dx > 1e-4:
            t = (right - x) / dx
            if t > 0.01 and t < min_t:
                test_y = y + t * dy
                if top - 2 <= test_y <= bottom + 2: min_t, side = t, 2
        if dy < -1e-4:
            t = (top - y) / dy
            if t > 0.01 and t < min_t:
                test_x = x + t * dx
                if left - 2 <= test_x <= right + 2: min_t, side = t, 3
        elif dy > 1e-4:
            t = (bottom - y) / dy
            if t > 0.01 and t < min_t:
                test_x = x + t * dx
                if left - 2 <= test_x <= right + 2: min_t, side = t, 4
        if side == 0 or min_t == float('inf') or min_t <= 0.01: break
        x = max(left, min(right, x + min_t * dx))
        y = max(top, min(bottom, y + min_t * dy))
        hits.append((round(x, 1), round(y, 1)))
        if side in (1, 2): dx = -dx
        else: dy = -dy
    return hits

hits = trace(500, 300, 600, 400, 100, 100, 900, 500, bounces=2)
assert hits == [(700.0, 500.0), (900.0, 300.0)], f"Unexpected hits: {hits}"
print("OK")
