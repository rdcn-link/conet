import copy
import json
from pathlib import Path
import unittest
import publish

class PublicationContracts(unittest.TestCase):
    def setUp(self):
        self.data=json.loads((Path(__file__).parent/'results.json').read_text())
    def test_incomplete_trials_and_failed_downloads_are_rejected(self):
        publish.validate(self.data)
        incomplete=copy.deepcopy(self.data);incomplete['samples'].pop()
        with self.assertRaises(ValueError):publish.validate(incomplete)
        wrong=copy.deepcopy(self.data)
        next(s for s in wrong['samples'] if s['method']=='ftp-download-local')['downloads'][0]['protocol']='dacp'
        with self.assertRaises(ValueError):publish.validate(wrong)
    def test_remote_results_require_recorded_full_comparisons(self):
        data=copy.deepcopy(self.data);data['config']['result_comparison']='cross-cpu-full-structure-v1'
        with self.assertRaises(ValueError):publish.validate(data)
        for sample in data['samples']:
            sample['comparison_to_reference']=dict(policy='cross-cpu-full-structure-v1',different_floats=0,differences=[])
        publish.validate(data)
        data['samples'][0]['comparison_to_reference'].update(different_floats=1,differences=[dict(path='/features/0/properties/y',absolute_difference=.01,tolerance=.0001)])
        with self.assertRaises(ValueError):publish.validate(data)

if __name__=='__main__':unittest.main()
