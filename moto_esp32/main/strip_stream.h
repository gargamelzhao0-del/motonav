#pragma once

namespace motonav {
// Track only successful, contiguous full-width transfers. Partial rectangles,
// errors, gaps and new frames must restart the panel address window.
class StripStream {
public:
    enum Plan { Partial, Start, Continue };
    Plan begin(int x1, int y1, int x2, int y2) {
        bool full_width = x1 == 0 && x2 == 466 && y1 >= 0 && y1 < y2 && y2 <= 466;
        Plan plan = !full_width ? Partial : (y1 != 0 && y1 == next_y_ ? Continue : Start);
        next_y_ = -1; // The next transfer is unknown until completion is queued successfully.
        return plan;
    }
    void submitted(int y_end) { next_y_ = y_end < 466 ? y_end : -1; }
private:
    int next_y_ = -1;
};
}
