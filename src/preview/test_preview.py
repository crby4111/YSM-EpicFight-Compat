"""Focused end-to-end checks against the real Java preview worker (no game launch)."""
import base64
import importlib.util
import json
import math
from pathlib import Path
import struct
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('motion_preview_server', Path(__file__).with_name('server.py'))
server = importlib.util.module_from_spec(spec)
spec.loader.exec_module(server)


class PreviewWorkerTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        runtime = server.prepare_runtime()
        cls.temporary = tempfile.TemporaryDirectory(dir=server.STATE, prefix='worker-test-')
        server.STATE = Path(cls.temporary.name)
        cls.root = str(server.make_demo())
        controller_file = Path(cls.root) / 'custom' / 'preview_demo' / 'controllers.json'
        controller_data = json.loads(controller_file.read_text(encoding='utf-8'))
        controller_data['animation_controllers']['player.preview_support_probe'] = {
            'states': {
                'default': {
                    'animations': ['idle'],
                    'transitions': [{
                        'active': "ctrl.death == 0 && ctrl.loop == 1 && "
                                  "ctrl.parcool_state == '' && math.sin(30) > 0.49 && "
                                  "ctrl.preview_unknown_action + math.preview_missing(1) == 0"
                    }]
                },
                'active': {'animations': ['swim'], 'blend_transition': 0.0}
            }
        }
        controller_data['animation_controllers']['player.preview_support_reference'] = {
            'states': {
                'default': {'animations': ['idle'], 'transitions': [{'active': '1'}]},
                'active': {'animations': ['swim'], 'blend_transition': 0.0}
            }
        }
        controller_file.write_text(json.dumps(controller_data), encoding='utf-8')
        cls.worker = server.Worker(runtime)

    @classmethod
    def tearDownClass(cls):
        cls.worker.close()
        cls.temporary.cleanup()

    def request(self, command, **extra):
        return self.worker.call(dict(command=command, root=self.root, model='preview_demo', **extra))

    def sample(self, **extra):
        return self.request('sample', epic='epicfight:biped/living/idle', seconds=.5,
                            facts={'movement':'idle'}, **extra)

    @staticmethod
    def matrices(result):
        raw = base64.b64decode(result['matrices'])
        return struct.unpack('<' + 'f' * (len(raw)//4), raw)

    def test_catalogue_lists_actual_models_and_animation_assets(self):
        result = self.request('catalogue')
        self.assertIn('preview_demo', result['models'])
        self.assertIn('epicfight:biped/living/swim', result['epic'])
        self.assertFalse(any('/data/' in name or '/pov/' in name for name in result['epic']))

    def test_mesh_has_texture_and_valid_indices(self):
        model = self.request('inspect')
        self.assertEqual(model['sourceBones'], model['mappedBones'])
        self.assertEqual(11, model['mappedBones'])
        self.assertEqual(len(model['owners'])*3, len(model['positions']))
        self.assertTrue(all(0 <= i < len(model['owners']) for i in model['triangles']))
        self.assertTrue(model['textures']['main'].startswith('data:image/png;base64,'))

    def test_empty_rules_preserve_equal_ef_outputs(self):
        result = self.sample(rules={})
        values = self.matrices(result)
        self.assertTrue(all(math.isfinite(value) for value in values))
        size = result['poses']*16
        self.assertEqual(result['frames']*2*size, len(values))
        for frame in range(result['frames']):
            start=frame*2*size
            self.assertEqual(values[start:start+size], values[start+size:start+size*2])

    def test_model_clip_changes_the_right_side(self):
        result = self.sample(mode='clip', clip='swim')
        values = self.matrices(result)
        size = result['poses']*16
        self.assertNotEqual(values[:size], values[size:size*2])
        self.assertIn('base=preview-clip', result['diagnostics'][0])

    def test_custom_extra_uses_the_same_rule_kernel(self):
        result = self.sample(rules={'extras':[{'id':'custom-cape','source':{'id':'preview:test'},
                  'target':{'bones':['Cape'],'channels':['rotation']}}]},
                externalAnimations={'preview:test':{'animation_length':1,'bones':{'Cape':{'rotation':[40,0,0]}}}})
        self.assertIn('extras=[custom-cape]', result['diagnostics'][0])
        self.assertIn('base=epic_fight', result['diagnostics'][0])

    def test_declared_controller_uses_real_state_transitions(self):
        model=self.request('inspect')
        self.assertIn('player.preview',model['controllers'])
        result=self.request('sample',epic='epicfight:biped/living/idle',seconds=.5,
            facts={'movement':'swim'},mode='controller',controller='player.preview')
        self.assertIn('base=preview-controller',result['diagnostics'][-1])
        values=self.matrices(result);size=result['poses']*16;start=(result['frames']-1)*size*2
        self.assertNotEqual(values[start:start+size],values[start+size:start+size*2])

    def test_support_diagnostics_use_the_real_worker_dispatch(self):
        request = {
            'epic': 'epicfight:biped/living/idle',
            'seconds': .5,
            'facts': {'movement': 'idle'},
            'mode': 'controller'
        }
        result = self.request('sample', controller='player.preview_support_probe', **request)
        reference = self.request(
            'sample', controller='player.preview_support_reference', **request)

        self.assertEqual(
            ['ctrl.preview_unknown_action', 'math.preview_missing'], result['unhandled'])
        self.assertEqual(reference['matrices'], result['matrices'])

    def test_bad_rule_and_animation_names_fail_explicitly(self):
        with self.assertRaises(ValueError):
            self.sample(rules={'extras':[{'id':'bad','source':{'id':'pose'},'when':{'is_attaking':True}}]})
        with self.assertRaises(ValueError):
            self.request('sample', epic='missing:not-real')

    def test_durations_and_missing_models_are_bounded(self):
        with self.assertRaises(ValueError):
            self.request('sample', epic='epicfight:biped/living/idle', seconds=500)
        with self.assertRaises(ValueError):
            self.worker.call({'command':'inspect','root':self.root,'model':'../escape'})


if __name__ == '__main__':
    unittest.main(verbosity=2)
