import unittest
from prepare_long_context import expected

class ExpectedValuesTest(unittest.TestCase):
    def test_mixed_statuses_keep_429_in_counts_and_latency_but_not_alert_numerator(self):
        self.assertEqual([5,2,1,2,1,150,50,1],expected([
            '1|api|200|10','2|api|429|20','3|api|404|30','4|api|503|40',
            '5|api|302|50','broken-record','6|api|700|1','   ']))
    def test_nearest_rank_and_minimum_samples_remain_separate(self):
        self.assertEqual([2,0,1,0,1,6,4,0],expected(['1|api|500|2','2|api|200|4']))
        self.assertEqual([3,0,1,2,0,60,30,0],expected(['1|api|429|10','2|api|429|20','3|api|200|30']))

if __name__=='__main__':unittest.main()
