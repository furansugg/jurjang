def trace(cx, cy, tx, ty, left, top, right, bottom, ball_radius=20, bounces=2):
    inner_l = left + ball_radius
    inner_t = top + ball_radius
    inner_r = right - ball_radius
    inner_b = bottom - ball_radius

    dx, dy = tx - cx, ty - cy
    length = (dx * dx + dy * dy) ** 0.5
    if length == 0: return []
    dx /= length; dy /= length
    hits = []
    x, y = tx, ty
    for _ in range(bounces):
        min_t, side = float('inf'), 0
        if dx < -1e-4:
            t = (inner_l - x) / dx
            if t > 0.01 and t < min_t:
                test_y = y + t * dy
                if inner_t - 2 <= test_y <= inner_b + 2: min_t, side = t, 1
        elif dx > 1e-4:
            t = (inner_r - x) / dx
            if t > 0.01 and t < min_t:
                test_y = y + t * dy
                if inner_t - 2 <= test_y <= inner_b + 2: min_t, side = t, 2
        if dy < -1e-4:
            t = (inner_t - y) / dy
            if t > 0.01 and t < min_t:
                test_x = x + t * dx
                if inner_l - 2 <= test_x <= inner_r + 2: min_t, side = t, 3
        elif dy > 1e-4:
            t = (inner_b - y) / dy
            if t > 0.01 and t < min_t:
                test_x = x + t * dx
                if inner_l - 2 <= test_x <= inner_r + 2: min_t, side = t, 4
        if side == 0 or min_t == float('inf') or min_t <= 0.01: break
        x = max(inner_l, min(inner_r, x + min_t * dx))
        y = max(inner_t, min(inner_b, y + min_t * dy))
        hits.append((round(x, 1), round(y, 1)))
        if side in (1, 2): dx = -dx
        else: dy = -dy
    return hits

hits = trace(500, 300, 600, 400, 100, 100, 900, 500, ball_radius=20, bounces=2)
assert hits == [(680.0, 480.0), (880.0, 280.0)], f"Unexpected hits: {hits}"
print("OK")
