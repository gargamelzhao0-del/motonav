#pragma once

namespace motonav {
// Track successful contiguous bands with identical columns. Column changes,
// errors, gaps and new frames restart the panel address window.
class StripStream {
public:
    enum Plan { Partial, Start, Continue };
    Plan begin(int x1, int y1, int x2, int y2) {
        bool valid = x1 >= 0 && x1 < x2 && x2 <= 466 && y1 >= 0 && y1 < y2 && y2 <= 466;
        Plan plan = !valid ? Partial : (x1 == left_ && x2 == right_ && y1 == next_y_ ? Continue : Start);
        next_y_ = -1; // The next transfer is unknown until completion is queued successfully.
        left_ = x1; right_ = x2; pending_end_ = valid ? y2 : -1;
        return plan;
    }
    void submitted(int y_end) {
        next_y_ = y_end == pending_end_ && y_end > 0 && y_end < 466 ? y_end : -1;
        pending_end_ = -1;
    }
private:
    int next_y_ = -1, left_ = -1, right_ = -1, pending_end_ = -1;
};
}
